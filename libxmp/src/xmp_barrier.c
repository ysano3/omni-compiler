/*
 * $TSUKUBA_Release: $
 * $TSUKUBA_Copyright:
 *  $
 */
#ifndef MPI_PORTABLE_PLATFORM_H
#define MPI_PORTABLE_PLATFORM_H
#endif 

#include "mpi.h"
#include "xmp_internal.h"

static void _XMP_barrier_NODES_ENTIRE(_XMP_nodes_t *nodes) {
  _XMP_RETURN_IF_SINGLE;

  if (nodes->is_member) {
    MPI_Barrier(*((MPI_Comm *)nodes->comm));
  }
}

static void _XMP_barrier_EXEC(void) {
  _XMP_RETURN_IF_SINGLE;

  MPI_Barrier(*((MPI_Comm *)(_XMP_get_execution_nodes())->comm));
}


void _XMP_barrier(_XMP_object_ref_t *desc)
{
  if (desc){

    if (_XMP_is_entire(desc)){
      if (desc->ref_kind == XMP_OBJ_REF_NODES){
	_XMP_barrier_NODES_ENTIRE(desc->n_desc);
      }
      else {
	_XMP_barrier_NODES_ENTIRE(desc->t_desc->onto_nodes);
      }
    }
    else {
      _XMP_nodes_t *n;
      _XMP_create_task_nodes(&n, desc);
      if (_XMP_test_task_on_nodes(n)){
      	_XMP_barrier_EXEC();
      	_XMP_end_task();
      }
      _XMP_finalize_nodes(n);
    }
  }
  else {
    _XMP_barrier_EXEC();
  }
}
