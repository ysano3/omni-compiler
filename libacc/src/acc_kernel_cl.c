#include <sys/stat.h>
#include "acc_internal.h"
#include "acc_internal_cl.h"
#include <stdio.h>
#include <sys/time.h>
#ifdef __USE_FPGA__
#include <tlog.h>
#endif

#define GET_STR(str) #str
#define SET_BUILD_OPTION(include_dir) const static char build_option[] = "-I" GET_STR(include_dir)

char *binary_buf;

SET_BUILD_OPTION(OMNI_INCLUDE_DIR);

struct _ACC_kernel_type
{
  char *name;
  cl_kernel kernel;
};

struct _ACC_program_type
{
  cl_program program;
  int num_kernels;
  _ACC_kernel_t *kernels;
};

#define _ACC_M_CEILi(a_, b_) (((a_) % (b_)) == 0 ? ((a_) / (b_)) : ((a_) / (b_)) + 1)
#define _ACC_M_ROUNDUP(a_, n_) ((_ACC_M_CEILi((a_), (n_))) * (n_))

static int adjust_num_gangs(int num_gangs, int limit)
{
  int n = num_gangs <= limit ? num_gangs : limit;
  return _ACC_M_ROUNDUP(n, 16); // num_threads must be multiple of 128 => num_pe must be multiple of 16
}

void _ACC_launch(_ACC_program_t *program, int kernel_num, int *_ACC_conf, int async_num, int num_args, unsigned long long /*instead of size_t*/ *arg_sizes, void **args)
{
  int loop;
  double total = 0.;

  for (loop = 0; loop < _ACC_profiling_loop; loop++)
  {
    cl_kernel kernel = program->kernels[kernel_num].kernel;

    int i;
    for (i = 0; i < num_args; i++)
    {
      CL_CHECK(clSetKernelArg(kernel, i, arg_sizes[i], args[i]));
    }

    int num_gangs = _ACC_conf[0];
    int vector_length = _ACC_conf[2];

#ifdef PEZY
    if (vector_length != 8)
    {
      _ACC_fatal("vector_length must be 8");
    }
    int adjusted_num_gangs = adjust_num_gangs(num_gangs, 1024);
    size_t global_work_size = adjusted_num_gangs * vector_length;
    size_t local_work_size = vector_length;
    _ACC_DEBUG("original num_gangs=%d, adjusted_num_gangs=%d\n", num_gangs, adjusted_num_gangs);
#elif defined __USE_FPGA__
    size_t global_work_size = 1;
    size_t local_work_size = 1;
#else
    size_t global_work_size = num_gangs * vector_length;
    size_t local_work_size = vector_length;
#endif

    _ACC_DEBUG("enqueue \"%s\" (%zd, %zd)\n", program->kernels[kernel_num].name, global_work_size, local_work_size)

    cl_event event;
    cl_event *ev_p = (async_num == ACC_ASYNC_SYNC) ? &event : NULL;
    _ACC_queue_t *queue = _ACC_queue_map_get_queue(async_num);
    cl_command_queue command_queue = _ACC_queue_get_command_queue(queue);
    // struct timeval start, end, result;
    // gettimeofday(&start, NULL);
    CL_CHECK(clEnqueueNDRangeKernel(command_queue, kernel, 1, NULL, &global_work_size, &local_work_size, 0, NULL, ev_p));
    // FIXME set last event  //_ACC_queue_set_last_event(event);

    // wait kernel execution
    if (async_num == ACC_ASYNC_SYNC)
    {
      // XXX is flush need?
      // CL_CHECK(clFlush(command_queue));
      CL_CHECK(clWaitForEvents(1, &event));
      // gettimeofday(&end, NULL);

      if (_ACC_get_timestamp) {
        unsigned long k_start, k_end;

        CL_CHECK(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &k_start, NULL));
        CL_CHECK(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &k_end, NULL));

#ifdef __USE_FPGA__
        tlog_log3(0, TLOG_PARALLEL_IN, k_start * 1e-9);
        tlog_log3(0, TLOG_PARALLEL_OUT, k_end * 1e-9);
#endif

        if(loop == 0) {
          printf("KERNEL(profile mode, 1st): time = %lfs\n", (k_end - k_start) * 1e-9);
        }

        total += (k_end - k_start) * 1e-9;
      }

      CL_CHECK(clReleaseEvent(event));
    }
/*
    result.tv_sec = end.tv_sec - start.tv_sec;
    result.tv_usec = end.tv_usec - start.tv_usec;
    if (result.tv_usec < 0)
    {
      result.tv_usec += 1000000;
      result.tv_sec -= 1;
    }
    if (_ACC_get_timestamp)
    {

#ifdef __USE_FPGA__
      // tlog_log3(0, TLOG_PARALLEL_IN, k_start * 10e-9);
      // tlog_log3(0, TLOG_PARALLEL_OUT, k_end * 10e-9);
      tlog_log3(0, TLOG_PARALLEL_IN, start.tv_sec + start.tv_usec * 1e-6);
      tlog_log3(0, TLOG_PARALLEL_OUT, end.tv_sec + end.tv_usec * 1e-6);
#endif
      if (loop == 0) {
        printf("KERNEL(gettimeofday, 1st): time = %lfs\n", result.tv_sec + result.tv_usec * 1e-6);
      }
      total += result.tv_sec + result.tv_usec * 1e-6;
    }
*/
  }
  if(_ACC_get_timestamp) {
    printf("KERNEL(profile mode, avg): time = %lfs\n", total / _ACC_profiling_loop);
  }
}

void _ACC_launch_multi(_ACC_program_t *program, int kernel_num_head, int *_ACC_conf, int async_num, int num_args, unsigned long long *arg_sizes, void **args, int num_kernels)
{
  cl_event *events;
  cl_event **ev_ps;
  _ACC_queue_t **queues;
  cl_command_queue *command_queues;

  if ((events = (cl_event *)malloc(sizeof(cl_event) * num_kernels)) == NULL)
  {
    _ACC_fatal("malloc");
  }
  if ((ev_ps = (cl_event **)malloc(sizeof(cl_event *) * num_kernels)) == NULL)
  {
    free(events);
    _ACC_fatal("malloc");
  }
  if ((queues = (_ACC_queue_t **)malloc(sizeof(_ACC_queue_t *) * num_kernels)) == NULL)
  {
    free(events);
    free(ev_ps);
    _ACC_fatal("malloc");
  }
  if ((command_queues = (cl_command_queue *)malloc(sizeof(cl_command_queue) * num_kernels)) == NULL)
  {
    free(queues);
    free(events);
    free(ev_ps);
    _ACC_fatal("malloc");
  }

  // struct timeval start, end, result;

  // gettimeofday(&start, NULL);

  int loop;
  double total = 0.;
  for(loop = 0; loop < _ACC_profiling_loop; loop++) {
    for (int j = 0; j < num_kernels; j++) {
      cl_kernel kernel = program->kernels[kernel_num_head + j].kernel;

      for (int i = 0; i < num_args; i++) {
        CL_CHECK(clSetKernelArg(kernel, i, arg_sizes[i], args[i + j * num_args]));
      }

      int num_gangs = _ACC_conf[0];
      int vector_length = _ACC_conf[2];
#ifdef __USE_FPGA__
      size_t global_work_size = 1;
      size_t local_work_size = 1;
#else
      size_t global_work_size = num_gangs * vector_length;
      size_t local_work_size = vector_length;
#endif

      _ACC_DEBUG("enqueue \"%s\" (%zd, %zd)\n", program->kernels[kernel_num_head + j].name, global_work_size, local_work_size)

      ev_ps[j] = (async_num == ACC_ASYNC_SYNC) ? &events[j] : NULL;
      queues[j] = _ACC_queue_map_get_queue_multi(async_num, j);
      command_queues[j] = _ACC_queue_get_command_queue(queues[j]);
      CL_CHECK(clEnqueueNDRangeKernel(command_queues[j], kernel, 1, NULL, &global_work_size, &local_work_size, 0, NULL, ev_ps[j]));
    }
    // wait kernel execution
    if (async_num == ACC_ASYNC_SYNC) {
      CL_CHECK(clWaitForEvents(num_kernels, events));

      if (_ACC_get_timestamp) {
        unsigned long k_start, k_end, k_temp;
        k_start = 0;
        k_end = 0;

        CL_CHECK(clGetEventProfilingInfo(events[0], CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &k_start, NULL));
        CL_CHECK(clGetEventProfilingInfo(events[0], CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &k_end, NULL));

        for (int j = 1; j < num_kernels; j++) {
          CL_CHECK(clGetEventProfilingInfo(events[j], CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &k_temp, NULL));
          k_start = k_start < k_temp ? k_start : k_temp;
          CL_CHECK(clGetEventProfilingInfo(events[j], CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &k_temp, NULL));
          k_end = k_end > k_temp ? k_end : k_temp;
        }

#ifdef __USE_FPGA__
        tlog_log3(0, TLOG_PARALLEL_IN, k_start * 1e-9);
        tlog_log3(0, TLOG_PARALLEL_OUT, k_end * 1e-9);
#endif

        if(loop == 0) {
          printf("KERNEL(profile mode, 1st): time = %lfs\n", (k_end - k_start) * 1e-9);
        }

        total += (k_end - k_start) * 1e-9;
      }

      for (int j = 0; j < num_kernels; j++) {
        CL_CHECK(clReleaseEvent(events[j]));
      }
    }

/*
    gettimeofday(&end, NULL);
    result.tv_sec = end.tv_sec - start.tv_sec;
    result.tv_usec = end.tv_usec - start.tv_usec;
    if (result.tv_usec < 0)
    {
      result.tv_usec += 1000000;
      result.tv_sec -= 1;
    }
    if (_ACC_get_timestamp) {
      printf("KERNEL(gettimeofday)     : time = %lfs\n", result.tv_sec + result.tv_usec * 1e-6);
    }
*/
  }
  if (_ACC_get_timestamp) {
    printf("KERNEL(profile mode, avg): time = %lfs\n", total / _ACC_profiling_loop);
  }


  free(queues);
  free(events);
  free(ev_ps);
  free(command_queues);
}

void _ACC_program_init_mem(_ACC_program_t **desc, char *kernel_bin_start, char *kernel_bin_end, int num_kernels, char **kernel_names);

void _ACC_program_init(_ACC_program_t **desc, char *kernel_src_filename, int num_kernels, char **kernel_names)
{
  // open kernel file
  FILE *fp = fopen(kernel_src_filename, "r");
  if (fp == NULL)
  {
    fprintf(stderr, "Failed to open kernel file %s.\n", kernel_src_filename);
    exit(1);
  }

  // get kernel file size
  struct stat filestat;
  stat(kernel_src_filename, &filestat);

  // read kernel file
  size_t kernel_src_size = filestat.st_size;
  char *kernel_src = (char *)_ACC_alloc(sizeof(char) * (kernel_src_size));
  size_t read_byte = fread(kernel_src, sizeof(char), kernel_src_size, fp);

  if (read_byte < kernel_src_size)
  {
    _ACC_fatal("faild to read kernel_file");
  }

  // close kernel source file
  fclose(fp);

  _ACC_DEBUG("filesize = %ld\n", kernel_src_size);
  _ACC_DEBUG("read bytes %zd\n", read_byte);
  //  _ACC_DEBUG("%s\n", kernel_src);

  // create program
  _ACC_DEBUG("create program \"%s\"\n", kernel_src_filename);

  _ACC_program_init_mem(desc, kernel_src, kernel_src + kernel_src_size, num_kernels, kernel_names);

  free(kernel_src);
}

void _ACC_program_init_mem(_ACC_program_t **desc, char *kernel_bin_start, char *kernel_bin_end, int num_kernels, char **kernel_names)
{
  _ACC_DEBUG("_ACC_program_init_mem(...)\n");
  _ACC_init_current_device_if_not_inited();

  _ACC_program_t *program = _ACC_alloc(sizeof(_ACC_program_t));
  cl_int ret;
  int i;

  size_t kernel_bin_size = kernel_bin_end - kernel_bin_start;

#ifdef PEZY
  {
    cl_int binary_status;
    _ACC_DEBUG("_ACC_cl_device_num=%d\n", _ACC_cl_device_num);
    program->program = clCreateProgramWithBinary(_ACC_cl_current_context, 1, &_ACC_cl_device_ids[_ACC_cl_device_num], &kernel_bin_size,
                                                 (const unsigned char **)&kernel_bin_start,
                                                 &binary_status, &ret);
    CL_CHECK(ret);
    CL_CHECK(binary_status);
  }
#elif defined __USE_FPGA__
  {
    // Read a Kernel code
    fprintf(stderr, "use aocx: %s\n", kernel_bin_start);
    FILE *fp = fopen(kernel_bin_start, "rb");
    if (fp == NULL)
    {
      _ACC_fatal("Failed to read binary");
    }

    fseek(fp, 0, SEEK_END);
    size_t binary_size = ftell(fp);
    rewind(fp);

    binary_buf = (char *)_ACC_alloc(sizeof(char) * binary_size);
    kernel_bin_size = fread(binary_buf, sizeof(char), binary_size, fp);
    fclose(fp);

    cl_int binary_status;
    _ACC_DEBUG("_ACC_cl_device_num=%d\n", _ACC_cl_device_num);
    program->program = clCreateProgramWithBinary(_ACC_cl_current_context, 1, &_ACC_cl_device_ids[_ACC_cl_device_num], &kernel_bin_size, (const unsigned char **)&binary_buf, &binary_status, &ret);
    CL_CHECK(ret);
    CL_CHECK(binary_status);
  }
  if (_ACC_get_timestamp)
  {
    double stamp;

    _ACC_get_inittime(&stamp);
    tlog_set_start(stamp);
    tlog_init(kernel_bin_start);
  }
#else
  program->program = clCreateProgramWithSource(_ACC_cl_current_context, 1, (const char **)&kernel_bin_start, &kernel_bin_size, &ret);
#endif
  CL_CHECK(ret);

  // build program
  ret = clBuildProgram(program->program, 1, &_ACC_cl_device_ids[_ACC_cl_device_num], build_option, NULL, NULL);

#if !defined PEZY && !defined __USE_FPGA__
  if (ret != CL_SUCCESS)
  {
    // print build error
    const int max_error_length = 1024 * 1024;
    size_t returned_size;
    char *error_log = _ACC_alloc(sizeof(char) * max_error_length + 1);
    CL_CHECK(clGetProgramBuildInfo(program->program,
                                   _ACC_cl_device_ids[_ACC_cl_device_num],
                                   CL_PROGRAM_BUILD_LOG,
                                   max_error_length, error_log, &returned_size));
    fprintf(stderr, "build log:\n%s\n", error_log);
    _ACC_free(error_log);
    exit(1);
  }
#endif

  // create kernels
  program->kernels = _ACC_alloc(sizeof(_ACC_kernel_t) * num_kernels);
  for (i = 0; i < num_kernels; i++)
  {
    _ACC_DEBUG("create kernel \"%s\"\n", kernel_names[i]);
    program->kernels[i].kernel = clCreateKernel(program->program, kernel_names[i], &ret);
    CL_CHECK(ret);
    program->kernels[i].name = kernel_names[i];
    // fprintf(stderr, "%s\n", kernel_names[i]);
  }

  program->num_kernels = num_kernels;

  *desc = program;
}

void _ACC_program_finalize(_ACC_program_t *program)
{
  int i;
  for (i = 0; i < program->num_kernels; i++)
  {
    CL_CHECK(clReleaseKernel(program->kernels[i].kernel));
  }
  _ACC_free(program->kernels);

  CL_CHECK(clReleaseProgram(program->program));

  _ACC_free(program);
#ifdef __USE_FPGA__
  _ACC_free(binary_buf);
#endif
}
