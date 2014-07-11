#ifndef _ACC_GPU
#define _ACC_GPU

#ifndef _ACC_CRAY
#include <stddef.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

  void _ACC_gpu_init(void);
  void _ACC_gpu_finalize(void);


  void _ACC_init_data(void **host_data_desc, void **device_addr, void *addr, size_t type_size, int dim, ...);  
  void _ACC_pinit_data(void **host_data_desc, void **device_addr, void *addr, size_t type_size, int dim, ...);
  void _ACC_find_data(void **host_data_desc, void **device_addr, void *addr, size_t type_size, int dim, ...);
  void _ACC_finalize_data(void *desc);
  void _ACC_pcopy_data(void *desc, int direction, int asyncId);
  void _ACC_copy_data(void *desc, int direction, int asyncId);
  void _ACC_copy_subdata(void *desc, int direction, int asyncId, ...);

  void _ACC_gpu_get_data(void **host_data_desc, void **device_addr, void *host_addr, size_t size);

  void _ACC_gpu_wait(int id);
  void _ACC_gpu_wait_all(void);

  void _ACC_gpu_alloc(void **addr, size_t size);
  void _ACC_gpu_malloc(void **addr, size_t size);
  void _ACC_gpu_calloc(void **addr, size_t size);
  void _ACC_gpu_free(void *addr);
  
#ifdef __cplusplus
}
#endif

#endif //_ACC_GPU
