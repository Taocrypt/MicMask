// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).

package main

// JNI entry points for com.taocrypt.micmask.ts.TsNative (see
// kotlin/TsNative.kt). Compiled into the same library on both Android and
// Windows, so the JVM side needs neither JNA nor an extra glue library.
// Strings cross as UTF-8 byte arrays (not jstring) to avoid JNI's
// "modified UTF-8".

/*
#include <jni.h>
#include <stdlib.h>
#include <string.h>

static char* mm_bytes(JNIEnv* env, jbyteArray a) {
	if (a == NULL) return NULL;
	jsize n = (*env)->GetArrayLength(env, a);
	char* r = (char*)malloc((size_t)n + 1);
	if (r == NULL) return NULL;
	(*env)->GetByteArrayRegion(env, a, 0, n, (jbyte*)r);
	r[n] = 0;
	return r;
}

static jbyteArray mm_newbytes(JNIEnv* env, const char* s, int n) {
	jbyteArray a = (*env)->NewByteArray(env, n);
	if (a != NULL && n > 0) (*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte*)s);
	return a;
}
*/
import "C"

import (
	"time"
	"unsafe"
)

func jb(env *C.JNIEnv, a C.jbyteArray) string {
	p := C.mm_bytes(env, a)
	if p == nil {
		return ""
	}
	defer C.free(unsafe.Pointer(p))
	return C.GoString(p)
}

func jout(env *C.JNIEnv, s string) C.jbyteArray {
	if s == "" {
		return C.mm_newbytes(env, nil, 0)
	}
	cs := C.CString(s)
	defer C.free(unsafe.Pointer(cs))
	return C.mm_newbytes(env, cs, C.int(len(s)))
}

func jbool(b C.jboolean) bool { return b != 0 }

//export Java_com_taocrypt_micmask_ts_TsNative_abiVersion
func Java_com_taocrypt_micmask_ts_TsNative_abiVersion(env *C.JNIEnv, _ C.jobject) C.jint {
	return abiVersion
}

//export Java_com_taocrypt_micmask_ts_TsNative_version
func Java_com_taocrypt_micmask_ts_TsNative_version(env *C.JNIEnv, _ C.jobject) C.jbyteArray {
	return jout(env, pluginVersion)
}

//export Java_com_taocrypt_micmask_ts_TsNative_setLogFile
func Java_com_taocrypt_micmask_ts_TsNative_setLogFile(env *C.JNIEnv, _ C.jobject, path C.jbyteArray) C.jint {
	return C.jint(setErr(doSetLog(jb(env, path))))
}

//export Java_com_taocrypt_micmask_ts_TsNative_start
func Java_com_taocrypt_micmask_ts_TsNative_start(env *C.JNIEnv, _ C.jobject, dir, host, key, ctrl C.jbyteArray, ephemeral, uploadLogs C.jboolean) C.jint {
	return C.jint(setErr(doStart(startOpts{jb(env, dir), jb(env, host), jb(env, key), jb(env, ctrl), jbool(ephemeral), jbool(uploadLogs)})))
}

//export Java_com_taocrypt_micmask_ts_TsNative_waitJson
func Java_com_taocrypt_micmask_ts_TsNative_waitJson(env *C.JNIEnv, _ C.jobject, timeoutMs C.jint) C.jbyteArray {
	return jout(env, toJSON(doWait(time.Duration(timeoutMs)*time.Millisecond)))
}

//export Java_com_taocrypt_micmask_ts_TsNative_statusJson
func Java_com_taocrypt_micmask_ts_TsNative_statusJson(env *C.JNIEnv, _ C.jobject, withPeers C.jboolean) C.jbyteArray {
	return jout(env, toJSON(doStatus(jbool(withPeers))))
}

//export Java_com_taocrypt_micmask_ts_TsNative_expose
func Java_com_taocrypt_micmask_ts_TsNative_expose(env *C.JNIEnv, _ C.jobject, port, localPort C.jint, udp C.jboolean) C.jint {
	return C.jint(setErr(doExpose(int(port), int(localPort), jbool(udp))))
}

//export Java_com_taocrypt_micmask_ts_TsNative_unexpose
func Java_com_taocrypt_micmask_ts_TsNative_unexpose(env *C.JNIEnv, _ C.jobject, port C.jint, udp C.jboolean) C.jint {
	return C.jint(setErr(doUnexpose(int(port), jbool(udp))))
}

//export Java_com_taocrypt_micmask_ts_TsNative_dial
func Java_com_taocrypt_micmask_ts_TsNative_dial(env *C.JNIEnv, _ C.jobject, host C.jbyteArray, port C.jint, udp C.jboolean) C.jint {
	lp, err := doDial(jb(env, host), int(port), jbool(udp))
	if setErr(err) != 0 {
		return -1
	}
	return C.jint(lp)
}

//export Java_com_taocrypt_micmask_ts_TsNative_undial
func Java_com_taocrypt_micmask_ts_TsNative_undial(env *C.JNIEnv, _ C.jobject, localPort C.jint, udp C.jboolean) C.jint {
	return C.jint(setErr(doUndial(int(localPort), jbool(udp))))
}

//export Java_com_taocrypt_micmask_ts_TsNative_stop
func Java_com_taocrypt_micmask_ts_TsNative_stop(env *C.JNIEnv, _ C.jobject) C.jint {
	return C.jint(setErr(doStop()))
}

//export Java_com_taocrypt_micmask_ts_TsNative_logout
func Java_com_taocrypt_micmask_ts_TsNative_logout(env *C.JNIEnv, _ C.jobject, wipe C.jboolean) C.jint {
	return C.jint(setErr(doLogout(jbool(wipe))))
}

//export Java_com_taocrypt_micmask_ts_TsNative_setInterfaces
func Java_com_taocrypt_micmask_ts_TsNative_setInterfaces(env *C.JNIEnv, _ C.jobject, js C.jbyteArray) C.jint {
	return C.jint(setErr(setInterfacesJSON(jb(env, js))))
}

//export Java_com_taocrypt_micmask_ts_TsNative_lastError
func Java_com_taocrypt_micmask_ts_TsNative_lastError(env *C.JNIEnv, _ C.jobject) C.jbyteArray {
	mu.Lock()
	e := lastErr
	mu.Unlock()
	return jout(env, e)
}
