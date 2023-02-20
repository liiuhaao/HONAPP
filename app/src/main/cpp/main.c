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

JNIEXPORT jobjectArray JNICALL
Java_com_example_honapp_UdpVpnService_encodef(JNIEnv *env, jobject thiz, jint k, jint n,
                                              jbyteArray send_array, jint send_size,
                                              jint symbol_size) {

    int len = (send_size + k - 1) / k;
    jbyte *array = malloc((len + 1) * sizeof(jbyte));
    memset(array, 0, sizeof(array));
    (*env)->GetByteArrayRegion(env, send_array, 0, send_size, array);


    char arr[n][symbol_size];
    for (int i = 0; i < k; i++) {
        memcpy(arr[i], array + i * symbol_size, symbol_size);
    }
    char *data[n];
    for (int i = 0; i < k; i++) {
        data[i] = arr[i];
    }


    rs_encode2(k, n, data, symbol_size);


    jclass cls = (*env)->FindClass(env, "[B");
    jobjectArray res_data = (*env)->NewObjectArray(env, n, cls, NULL);
    return res_data;

    for (int i = 0; i < n; i++) {
        jbyteArray symbol = (*env)->NewByteArray(env, symbol_size);
        jbyte *buf = (*env)->GetByteArrayElements(env, symbol, NULL);
        strcpy(buf, data[i]);
        (*env)->ReleaseByteArrayElements(env, symbol, buf, 0);
    }
    return res_data;
}

void log_data(char **data, char *tag, int n, int symbol_size) {
    LOGD("--------%s data start------------symbol_size=%d", tag, symbol_size);
    char log[2000 * 4] = "";
    int pos = 0;
    for (int i = 0; i < n; i++) {
        if (!data[i]) {
            LOGD("null");
            pos = 0;
            continue;
        }
        for (int j = 0; j < symbol_size; j++) {
            int tmp = data[i][j];
            if (tmp < 0) {
                log[pos++] = '-';
                tmp = -tmp;
            }
//            LOGD("i=%d, j=%d 100 tmp=%d", i, j, tmp);
            if (tmp >= 100) {
                log[pos++] = tmp / 100 + '0';
            }
            tmp -= tmp / 100 * 100;
//            LOGD("i=%d, j=%d 10 tmp=%d", i, j, tmp);
            if (tmp >= 10) {
                log[pos++] = tmp / 10 + '0';
            }
            tmp -= tmp / 10 * 10;
//            LOGD("i=%d, j=%d 1 tmp=%d", i, j, tmp);
            log[pos++] = tmp + '0';
            if (j == symbol_size - 1) {
                log[pos] = 0;
                LOGD("%s", log);
                pos = 0;
            } else {
                log[pos++] = ',';
            }
        }
        log[symbol_size * 2 + 1] = 0;
    }
    log[pos] = 0;
    LOGD("--------%s data end------------", tag);
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_honapp_UdpVpnService_encode(JNIEnv *env, jobject thiz, jint k, jint n,
                                             jobject send_buffer, jint symbol_size) {

    jbyte *pBuffer = (jbyte *) ((*env)->GetDirectBufferAddress(env, send_buffer));

    char arr[n][symbol_size];
    memset(arr, 0, sizeof(arr));
    for (int i = 0; i < k; i++) {
        memcpy(arr[i], pBuffer + i * symbol_size, symbol_size);
    }

//    char a[15][100] =
//            {
//                    {69,  0,   0,    40, 1},
//                    {78,  64,  0,    64, 6},
//                    {29,  10,  10,   0,  2},
//                    {15,  8,   8,    8,  8},
//                    {-96, -80, 3,    85, 61},
//                    {4,   114, -121, 25, 57},
//                    {53,  66,  80,   16, -1},
//                    {-1,  -15, -87,  0,  0},
//            };
//    char arr[n][symbol_size];
//    memset(arr, 0, sizeof(arr));
//    for (int i = 0; i < k; i++) {
//        memcpy(arr[i], a[i], symbol_size);
//    }

    char *data[n];
    for (int i = 0; i < n; i++) {
        data[i] = arr[i];
    }

//    log_data(data, "origin", n, symbol_size);

    rs_encode2(k, n, data, symbol_size);


    log_data(data, "encode", n, symbol_size);

    jclass cls = (*env)->FindClass(env, "[B");
    jobjectArray res_data = (*env)->NewObjectArray(env, n, cls, NULL);

    for (int i = 0; i < n; i++) {
        jbyteArray symbol = (*env)->NewByteArray(env, symbol_size);
        (*env)->SetByteArrayRegion(env, symbol, 0, symbol_size, (jbyte *) data[i]);
        (*env)->SetObjectArrayElement(env, res_data, i, symbol);
    }
    return res_data;
}


JNIEXPORT jobjectArray JNICALL
Java_com_example_honapp_UdpVpnService_decode(JNIEnv *env, jobject thiz, jint k, jint n,
                                             jobjectArray encode_data, jint symbol_size) {
    jbyte arr[n][symbol_size];
    char *data[n];
    for (int i = 0; i < n; i++) {
        jbyteArray symbol = (*env)->GetObjectArrayElement(env, encode_data, i);
        if (symbol) {
            (*env)->GetByteArrayRegion(env, symbol, 0, symbol_size, arr[i]);
            data[i] = (char *) arr[i];
        } else {
            data[i] = NULL;
        }
    }
    log_data((char **) data, "Before Decode", n, symbol_size);
    rs_decode2(k, n, (char **) data, symbol_size);
    log_data((char **) data, "After Decode", n, symbol_size);

    jclass cls = (*env)->FindClass(env, "[B");
    jobjectArray res_data = (*env)->NewObjectArray(env, k, cls, NULL);
    for (int i = 0; i < k; i++) {
        jbyteArray symbol = (*env)->NewByteArray(env, symbol_size);
        (*env)->SetByteArrayRegion(env, symbol, 0, symbol_size, (jbyte *) data[i]);
        (*env)->SetObjectArrayElement(env, res_data, i, symbol);
    }
    return res_data;
}