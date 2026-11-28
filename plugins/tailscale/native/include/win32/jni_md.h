/* Minimal jni_md.h for cross-compiling the plugin DLL with MinGW on a Linux
 * host (the Linux JDK only ships include/linux/jni_md.h). Matches the
 * definitions of OpenJDK's include/win32/jni_md.h for x64. */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall
typedef int jint;
typedef long long jlong;
typedef signed char jbyte;
#endif
