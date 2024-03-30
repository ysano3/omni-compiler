#include <stdio.h>
#include "acc_internal.h"
#include "acc_internal_cl.h"
#include "acc_data_struct.h"
#include "tlog.h"

/* temporal definitions */
static bool is_pagelocked(void *host_p)
{
  return false;
}
static void register_memory(void *host_p, size_t size)
{
}
static void unregister_memory(void *host_p)
{
}
static void pagelock(_ACC_memory_t *data)
{
  if(data->is_pagelocked == false && data->is_registered == false){
    register_memory(data->host_addr, data->size);
    data->is_registered = true;
  }
}
/* end of temporal definitions */

_ACC_memory_t* _ACC_memory_alloc(void *host_addr, size_t size, void *memory_object)
{
  _ACC_init_current_device_if_not_inited();
  _ACC_memory_t *memory = (_ACC_memory_t *)_ACC_alloc(sizeof(_ACC_memory_t));
  memory->host_addr = host_addr;
  if(memory_object != NULL){
    memory->memory_object = memory_object;
    memory->is_alloced = false;
  }else{
    //device memory alloc
    ////_ACC_gpu_alloc(&(memory->device_addr), size);
    cl_int ret;
    memory->memory_object = clCreateBuffer(_ACC_cl_current_context, CL_MEM_READ_WRITE, size, NULL, &ret);
    CL_CHECK(ret);

    memory->is_alloced = true;
  }
  memory->size = size;
  memory->ref_count = 0;

  //for memory attribute
  memory->is_pagelocked = is_pagelocked(memory->host_addr);
  memory->is_registered = false;

  return memory;
}

void _ACC_memory_free(_ACC_memory_t* memory)
{
  if(memory->is_alloced){
    //_ACC_gpu_free(memory->device_addr);
    CL_CHECK(clReleaseMemObject(memory->memory_object));
  }
  if(memory->is_registered){
    unregister_memory(memory->host_addr);
  }
  _ACC_free(memory);
}

void _ACC_cl_copy(void *host_addr, cl_mem memory_object, size_t mem_offset, size_t size, int direction, int asyncId)
{
  /* mem_offset is memory_object's offset and NOT host_addr's offset*/

  cl_bool is_blocking;
  if(asyncId == ACC_ASYNC_SYNC){
    is_blocking = CL_TRUE;
  }else{
    is_blocking = CL_FALSE;
  }

  _ACC_queue_t *queue = _ACC_queue_map_get_queue(asyncId);
  cl_command_queue command_queue = _ACC_queue_get_command_queue(queue);

  if(_ACC_get_timestamp) {
    cl_event event;
    cl_event *ev_p = &event;
    struct timeval start, end, result;

    // int ref;
    // CL_CHECK(clGetMemObjectInfo(memory_object, CL_MEM_REFERENCE_COUNT, sizeof(cl_int), &ref, NULL));
    // fprintf(stderr, "ref = %d\n", ref);
    if(direction == _ACC_COPY_HOST_TO_DEVICE){
      _ACC_DEBUG("HostToDevice: from=(host)%p, to=(device)%p+%zd, size=%zd,   is_blocking=%d\n", host_addr, memory_object, mem_offset, size, is_blocking);
      gettimeofday(&start, NULL);
      CL_CHECK(clEnqueueWriteBuffer(command_queue, memory_object, is_blocking, mem_offset, size, host_addr, 0 /*num_wait_ev*/, NULL, ev_p));
    }else if(direction == _ACC_COPY_DEVICE_TO_HOST){
      _ACC_DEBUG("DeviceToHost: from=(device)%p+%zd, to=(host)%p, size=%zd,   is_blocking=%d\n", memory_object, mem_offset, host_addr, size, is_blocking);
      gettimeofday(&start, NULL);
      CL_CHECK(clEnqueueReadBuffer(command_queue, memory_object, is_blocking, mem_offset, size, host_addr, 0 /*num_wait_ev*/, NULL, ev_p));
    }else{
      _ACC_FATAL("invalid direction\n");
    }

    CL_CHECK(clWaitForEvents(1, &event));
    gettimeofday(&end, NULL);

    unsigned long k_start, k_end;

    CL_CHECK(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &k_start, NULL));
    CL_CHECK(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &k_end, NULL));

    result.tv_sec = end.tv_sec - start.tv_sec;
    result.tv_usec = end.tv_usec - start.tv_usec;
    if(result.tv_usec < 0) {
      result.tv_usec += 1000000;
      result.tv_sec -= 1;
    }

    if (direction == _ACC_COPY_HOST_TO_DEVICE) {
#ifdef __USE_FPGA__
      tlog_log3(0, TLOG_EVENT_1_IN, k_start * 1e-9);
      tlog_log3(0, TLOG_EVENT_1_OUT, k_end * 1e-9);
      // tlog_log3(0, TLOG_EVENT_1_OUT, start.tv_sec + start.tv_usec * 1e-6);
      // tlog_log3(0, TLOG_EVENT_1_OUT, end.tv_sec + end.tv_usec * 1e-6);
#endif

      printf("COPY_HtoD(profile mode)  : time = %lfs\n", (k_end - k_start) * 1e-9);
      // printf("COPY_HtoD(gettimeofday)  : time = %lfs\n", result.tv_sec + result.tv_usec * 1e-6);
    } else {
#ifdef __USE_FPGA__
      tlog_log3(0, TLOG_EVENT_2_IN, k_start * 1e-9);
      tlog_log3(0, TLOG_EVENT_2_OUT, k_end * 1e-9);
      // tlog_log3(0, TLOG_EVENT_2_OUT, start.tv_sec + start.tv_usec * 1e-6);
      // tlog_log3(0, TLOG_EVENT_2_OUT, end.tv_sec + end.tv_usec * 1e-6);
#endif

      printf("COPY_DtoH(profile mode)  : time = %lfs\n", (k_end - k_start) * 1e-9);
      // printf("COPY_DtoH(gettimeofday)  : time = %lfs\n", result.tv_sec + result.tv_usec * 1e-6);
    }
    CL_CHECK(clReleaseEvent(event));
  } else {
    if(direction == _ACC_COPY_HOST_TO_DEVICE){
      _ACC_DEBUG("HostToDevice: from=(host)%p, to=(device)%p+%zd, size=%zd,   is_blocking=%d\n", host_addr, memory_object, mem_offset, size, is_blocking);
      CL_CHECK(clEnqueueWriteBuffer(command_queue, memory_object, is_blocking,  mem_offset, size, host_addr, 0 /*num_wait_ev*/, NULL, NULL));
    }else if(direction == _ACC_COPY_DEVICE_TO_HOST){
      _ACC_DEBUG("DeviceToHost: from=(device)%p+%zd, to=(host)%p, size=%zd,   is_blocking=%d\n", memory_object, mem_offset, host_addr, size, is_blocking);
      CL_CHECK(clEnqueueReadBuffer(command_queue, memory_object, is_blocking,   mem_offset, size, host_addr, 0 /*num_wait_ev*/, NULL, NULL));
    }else{
      _ACC_FATAL("invalid direction\n");
    }
  }
}

void _ACC_memory_copy(_ACC_memory_t *data, ptrdiff_t offset, size_t size, int direction, int asyncId)
{
  void *host_addr = ((char*)(data->host_addr) + offset);
  cl_mem memory_object = data->memory_object;

  if(asyncId != ACC_ASYNC_SYNC){
    pagelock(data);
  }

  _ACC_cl_copy(host_addr, memory_object, offset, size, direction, asyncId);
}

void _ACC_memory_copy_sub(_ACC_memory_t* memory, ptrdiff_t memory_offset, int direction, int isAsync,
			  size_t type_size, int dim, int pointer_dim_bit,
			  unsigned long long offsets[],
			  unsigned long long lowers[],
			  unsigned long long lengths[],
			  unsigned long long distance[])
{
  _ACC_fatal("_ACC_memory_copy_sub is unimplemented for OpenCL");
}
void _ACC_memory_copy_vector(_ACC_memory_t *data, size_t memory_offset, int direction, int asyncId, size_t type_size, unsigned long long offset, unsigned long long count, unsigned long long blocklength, unsigned long long stride)
{
  _ACC_fatal("_ACC_memory_copy_vector is unimplemented for OpenCL");
}


// refcount functions
void _ACC_memory_increment_refcount(_ACC_memory_t *data)
{
  ++data->ref_count;
}
void _ACC_memory_decrement_refcount(_ACC_memory_t *data)
{
  if(data->ref_count == 0){
    _ACC_fatal("ref_count is alreadly 0\n");
  }
  --data->ref_count;
}
unsigned int _ACC_memory_get_refcount(_ACC_memory_t* memory)
{
  return memory->ref_count;
}


void* _ACC_memory_get_host_addr(_ACC_memory_t* memory)
{
  return memory->host_addr;
}
size_t _ACC_memory_get_size(_ACC_memory_t* memory)
{
  return memory->size;
}
ptrdiff_t _ACC_memory_get_host_offset(_ACC_memory_t* data, void *host_addr)
{
  return (char*)data->host_addr - (char*)host_addr;
}
void* _ACC_memory_get_device_addr(_ACC_memory_t* data, ptrdiff_t offset)
{
  if(offset != 0){
    fprintf(stderr, "test: sub memory object is unsupported now\n");
    // _ACC_fatal("sub memory object is unsupported now");
  }
  return data->memory_object;
}


void _ACC_memory_set_pointees(_ACC_memory_t* memory, int num_pointers, _ACC_memory_t** pointees, ptrdiff_t* pointee_offsets, void *device_pointee_pointers)
{
  _ACC_fatal("pointer data is not supported");
}

bool _ACC_memory_is_pointer(_ACC_memory_t* memory)
{
  return false;
}

_ACC_memory_t** _ACC_memory_get_pointees(_ACC_memory_t* memory)
{
  _ACC_fatal("pointer data is not supported");
  return NULL;
}

unsigned int _ACC_memory_get_num_pointees(_ACC_memory_t* memory)
{
  _ACC_fatal("pointer data is not supported");
  return 0;
}

void _ACC_get_inittime(double *stamp) {
  int dummy = 0;
  void *_ACC_HOST_DESC_dummy;
  void *_ACC_DEVICE_ADDR_dummy;
  unsigned long long _ACC_funcarg_0[] = {0};
  unsigned long long _ACC_funcarg_1[] = {1};

  _ACC_init_data(&(_ACC_HOST_DESC_dummy), &(_ACC_DEVICE_ADDR_dummy), &dummy, sizeof(int), 1, 0, _ACC_funcarg_0, _ACC_funcarg_1);

  _ACC_queue_t *queue = _ACC_queue_map_get_queue(ACC_ASYNC_SYNC);
  cl_command_queue command_queue = _ACC_queue_get_command_queue(queue);
  cl_event event;
  cl_event *ev_p = &event;
  _ACC_data_t *desc = (_ACC_data_t*)_ACC_HOST_DESC_dummy;
  _ACC_memory_t *data = desc->memory;

  void *host_addr = (char*)(data->host_addr);
  cl_mem memory_object = data->memory_object;

  CL_CHECK(clEnqueueWriteBuffer(command_queue, memory_object, CL_TRUE, 0, sizeof(cl_int), host_addr, 0 /*num_wait_ev*/, NULL, ev_p));

  CL_CHECK(clWaitForEvents(1, &event));
  long k_end;
  // struct timeval end;
  CL_CHECK(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &k_end, NULL));
  // gettimeofday(&end, NULL);
  *stamp = k_end * 1e-9;
  // *stamp = end.tv_sec + end.tv_usec * 1e-6;

  _ACC_finalize_data(_ACC_HOST_DESC_dummy, 0);
}

void _ACC_get_fintime(double *stamp) {
  int dummy = 0;
  void *_ACC_HOST_DESC_dummy;
  void *_ACC_DEVICE_ADDR_dummy;
  unsigned long long _ACC_funcarg_0[] = {0};
  unsigned long long _ACC_funcarg_1[] = {1};

  _ACC_init_data(&(_ACC_HOST_DESC_dummy), &(_ACC_DEVICE_ADDR_dummy), &dummy, sizeof(int), 1, 0, _ACC_funcarg_0, _ACC_funcarg_1);

  _ACC_queue_t *queue = _ACC_queue_map_get_queue(ACC_ASYNC_SYNC);
  cl_command_queue command_queue = _ACC_queue_get_command_queue(queue);
  cl_event event;
  cl_event *ev_p = &event;
  _ACC_data_t *desc = (_ACC_data_t*)_ACC_HOST_DESC_dummy;
  _ACC_memory_t *data = desc->memory;

  void *host_addr = (char*)(data->host_addr);
  cl_mem memory_object = data->memory_object;

  CL_CHECK(clEnqueueWriteBuffer(command_queue, memory_object, CL_TRUE, 0, sizeof(cl_int), host_addr, 0 /*num_wait_ev*/, NULL, ev_p));

  CL_CHECK(clWaitForEvents(1, &event));
  unsigned long k_end;
  // struct timeval end;
  CL_CHECK(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &k_end, NULL));
  // gettimeofday(&end, NULL);
  *stamp = k_end * 1e-9;
  // *stamp = end.tv_sec + end.tv_usec * 1e-6;

  _ACC_finalize_data(_ACC_HOST_DESC_dummy, 0);
}
