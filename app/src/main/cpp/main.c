//
// Created by liuhao on 2023/2/17.
//

#include "main.h"
#include "stdio.h"
#include "stdlib.h"
#include "string.h"
#include <jni.h>
#include <android/log.h>

#define LOG_TAG "main.c"

// 定义debug信息
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
// 定义error信息
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
// 定义info信息
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)


void log_data(unsigned char **data_block, char *tag, int block_num, int block_size) {
    LOGD("--------%s data_block start------------block_size=%d", tag, block_size);
    char log[2000 * 4] = "";
    int pos = 0;
    for (int i = 0; i < block_num; i++) {
        if (!data_block[i]) {
            LOGD("null");
            pos = 0;
            continue;
        }
        for (int j = 0; j < block_size; j++) {
            int tmp = data_block[i][j];
            if (tmp < 0) {
                log[pos++] = '-';
                tmp = -tmp;
            }
            if (tmp >= 100) {
                log[pos++] = tmp / 100 + '0';
            }
            tmp -= tmp / 100 * 100;
            if (tmp >= 10) {
                log[pos++] = tmp / 10 + '0';
            }
            tmp -= tmp / 10 * 10;
            log[pos++] = tmp + '0';
            if (j == block_size - 1) {
                log[pos] = 0;
                LOGD("%s", log);
                pos = 0;
            } else {
                log[pos++] = ',';
            }
        }
        log[block_size * 2 + 1] = 0;
    }
    log[pos] = 0;
    LOGD("--------%s data_block end------------", tag);
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_honapp_HONFecService_encode(JNIEnv *env, jobject thiz, jint data_num,
                                             jint block_num,
                                             jobjectArray packet_buffers, jint block_size) {


    jbyte arr[block_num][block_size];
    memset(arr, 0, sizeof(arr));
    unsigned char *data_blocks[block_num];
    for (int i = 0; i < block_num; i++) {
        if (i < data_num) {
            jbyteArray byteArray = (jbyteArray) (*env)->GetObjectArrayElement(env, packet_buffers,
                                                                              i);
            memcpy(arr[i], (unsigned char *) (*env)->GetByteArrayElements(env, byteArray, NULL),
                   block_size);
        }
        data_blocks[i] = (unsigned char *) arr[i];
    }

//    log_data(data_blocks, "origin", block_num, block_size);

    reed_solomon *rs = reed_solomon_new(data_num, block_num - data_num);
    reed_solomon_encode2(rs, data_blocks, block_num, block_size);

//    log_data(data_blocks, "encode", block_num, block_size);

//    unsigned char marks[block_num];
//    memset(marks,0,sizeof(marks));
//    for (int i = 0; i < 5; i++) {
//        marks[i] = 1;
//        memset(data_blocks[i], 0, block_size);
//    }
//
//    log_data(data_blocks, "receive", block_num, block_size);
//    reed_solomon *rss = reed_solomon_new(data_num, block_num - data_num);
//    reed_solomon_reconstruct(rss, data_blocks, marks, block_num,
//                             block_size);
//
//    log_data(data_blocks, "decode", block_num, block_size);

    jclass cls = (*env)->FindClass(env, "[B");
    int parity_num = block_num - data_num;
    jobjectArray res_data = (*env)->NewObjectArray(env, parity_num, cls, NULL);

    for (int i = 0; i < parity_num; i++) {
        jbyteArray block = (*env)->NewByteArray(env, block_size);
        (*env)->SetByteArrayRegion(env, block, 0, block_size, (jbyte *) data_blocks[data_num + i]);
        (*env)->SetObjectArrayElement(env, res_data, i, block);
    }
    return res_data;
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_honapp_HONFecService_decode(JNIEnv *env, jobject thiz, jint data_num,
                                             jint block_num, jobjectArray encode_data,
                                             jint block_size) {
    jbyte arr[block_num][block_size];
    unsigned char *data_blocks[block_num];
    unsigned char marks[block_num];
    memset(arr, 0, sizeof(arr));
    memset(marks, 0, block_num);
    for (int i = 0; i < block_num; i++) {
        jbyteArray block = (*env)->GetObjectArrayElement(env, encode_data, i);
        if (block) {
            (*env)->GetByteArrayRegion(env, block, 0, block_size, arr[i]);
            data_blocks[i] = (unsigned char *) arr[i];
        } else {
            data_blocks[i] = (unsigned char *) arr[i];
            marks[i] = 1;
        }
    }

    log_data((unsigned char **) (char **) data_blocks, "Before Decode", block_num, block_size);

    if (block_num > data_num) {
        reed_solomon *rs = reed_solomon_new(data_num, block_num - data_num);
        reed_solomon_reconstruct(rs, data_blocks, marks, block_num,
                                 block_size);
    }

    log_data((unsigned char **) (char **) data_blocks, "After Decode", block_num, block_size);

    jclass cls = (*env)->FindClass(env, "[B");
    jobjectArray res_data = (*env)->NewObjectArray(env, data_num, cls, NULL);
    for (int i = 0; i < data_num; i++) {
        jbyteArray symbol = (*env)->NewByteArray(env, block_size);
        (*env)->SetByteArrayRegion(env, symbol, 0, block_size, (jbyte *) data_blocks[i]);
        (*env)->SetObjectArrayElement(env, res_data, i, symbol);
    }
    return res_data;
}

JNIEXPORT void JNICALL
Java_com_example_honapp_HONFecService_fecInit(JNIEnv *env, jobject thiz) {
    fec_init();
}