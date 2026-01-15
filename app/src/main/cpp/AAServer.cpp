// Write C++ code here.
//
// Do not forget to dynamically load the C++ library into your application.
//
// For instance,
//
// In MainActivity.java:
//    static {
//       System.loadLibrary("AAServer");
//    }
//
// Or, in MainActivity.kt:
//    companion object {
//      init {
//         System.loadLibrary("AAServer")
//      }
//    }

#include "AAServer.h"
#include <jni.h>

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_manum45_openaa_MainActivity_00024Companion_AAServerHello(JNIEnv *env, jobject thiz) {
    // TODO: implement AAServerHello()
    return 0xDEAD;
}