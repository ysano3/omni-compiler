/*
 * $TSUKUBA_Release: Omni OpenMP Compiler 3 $
 * $TSUKUBA_Copyright:
 *  PLEASE DESCRIBE LICENSE AGREEMENT HERE
 *  $
 */
#ifndef _TLOG_H
#define _TLOG_H

#ifdef __USE_FPGA__
#define MAX_THREADS 4
#else
#define MAX_THREADS 64
#endif
#define TLOG_FILE_NAME "t.log"

#define TLOG_BLOCK_SIZE 1024
typedef struct _TLOG_BLOCK {
    struct _TLOG_BLOCK *next;
    double data[TLOG_BLOCK_SIZE/sizeof(double)];
} TLOG_BLOCK;

#ifdef __USE_FPGA__
#include "exc_platform.h"
#else
typedef short _omInt16_t;
typedef int _omInt32_t;
#endif

#ifdef __USE_FPGA__
typedef enum tlog_type
{
    TLOG_UNDEF = 0, /* undefined */
    TLOG_START = 1,
    TLOG_END = 2, /* END*/

    TLOG_EVENT_IN = 20,
    TLOG_EVENT_OUT = 21,
    TLOG_FUNC_IN = 14,
    TLOG_FUNC_OUT = 15,
    TLOG_BARRIER_IN = 16,
    TLOG_BARRIER_OUT = 17,
    TLOG_PARALLEL_IN = 24,
    TLOG_PARALLEL_OUT = 25,
    TLOG_CRITICAL_IN = 28,
    TLOG_CRITICAL_OUT = 29,
    TLOG_LOOP_INIT_EVENT = 10,
    TLOG_LOOP_NEXT_EVENT = 11,
    TLOG_SECTION_EVENT = 12,
    TLOG_SIGNLE_EVENT = 13,
    TLOG_EVENT_1_IN = 22,
    TLOG_EVENT_1_OUT = 23,
    TLOG_EVENT_2_IN = 26,
    TLOG_EVENT_2_OUT = 27,

    TLOG_RAW = 31, /* RAW information */
    TLOG_EVENT = 32,
    TLOG_EVENT_1 = 33,
    TLOG_EVENT_2 = 34,
    TLOG_EVENT_3 = 35,
    TLOG_EVENT_4 = 36,
    TLOG_EVENT_5 = 37,
    TLOG_EVENT_6 = 38,
    TLOG_EVENT_7 = 39,

    TLOG_END_END
} TLOG_TYPE;
#else
typedef enum tlog_type
{
    TLOG_UNDEF = 0, /* undefined */
    TLOG_END = 1,   /* END*/
    TLOG_START = 2,
    TLOG_RAW = 3, /* RAW information */
    TLOG_EVENT = 4,
    TLOG_EVENT_IN = 5,
    TLOG_EVENT_OUT = 6,
    TLOG_FUNC_IN = 7,
    TLOG_FUNC_OUT = 8,
    TLOG_BARRIER_IN = 9,
    TLOG_BARRIER_OUT = 10,
    TLOG_PARALLEL_IN = 11,
    TLOG_PARALLEL_OUT = 12,
    TLOG_CRITICAL_IN = 13,
    TLOG_CRITICAL_OUT = 14,
    TLOG_LOOP_INIT_EVENT = 15,
    TLOG_LOOP_NEXT_EVENT = 16,
    TLOG_SECTION_EVENT = 17,
    TLOG_SIGNLE_EVENT = 18,
    TLOG_END_END
} TLOG_TYPE;
#endif

/* every log record is 2 double words. */
#ifdef __USE_FPGA__
typedef struct tlog_record {
    short proc_id;  /* processor id */
    short log_type; /* major type */
    _omInt16_t arg1; /* minor type */
    _omInt16_t arg2;
    double time_stamp;
} TLOG_DATA;
#else
typedef struct tlog_record {
    char log_type;	/* major type */
    char proc_id;   /* processor id */
    _omInt16_t arg1; /* minor type */
    _omInt32_t arg2;
    double time_stamp;
} TLOG_DATA;
#endif

typedef struct tlog_handle {
    TLOG_BLOCK *block_top;
    TLOG_BLOCK *block_tail;
    TLOG_DATA *free_p;
    TLOG_DATA *end_p;
} TLOG_HANDLE;

extern TLOG_HANDLE tlog_handle_table[];

/* prototypes */
void tlog_set_start(double stamp);
void tlog_init(char *name);
void tlog_finalize(void);
void tlog_finalize_fpga(double);
void tlog_log(int id,enum tlog_type type);
void tlog_log1(int id,TLOG_TYPE type,int arg1);
void tlog_log2(int id, TLOG_TYPE type, int arg1, int arg2);
void tlog_log3(int id, TLOG_TYPE type, double stamp);
double tlog_timestamp(void);

#endif /* _TLOG_H */
