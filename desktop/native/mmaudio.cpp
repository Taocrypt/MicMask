// MicMask 电脑版 native 音频库（mmaudio.dll，x64，Windows 10+）。GPL-3.0，随 MicMask 源码发布。
//
// 用 WASAPI 共享模式做低延迟采集/播放，并支持「按进程抓声音」（Windows 10 2004 / build 19041 起的
// AUDIOCLIENT_PROCESS_LOOPBACK，可包含或排除目标进程树）。Java 端用 JNA 调这些 C 函数（见 NativeAudio.kt），
// 任何一步失败都返回错误，Java 端自动回退到 javax.sound（DirectSound）。
//
// 设计：
//  - 每条流一个 native 线程（COM MTA + MMCSS「Pro Audio」），事件驱动；与 Java 之间用 48 kHz 单声道 int16 环形缓冲交换。
//  - 采集：设备混音格式的声道数 + 48 kHz float32（AUTOCONVERTPCM 让系统做重采样），native 里下混成单声道：
//    只平均「有信号」的声道（比最响声道低 20 dB 以内），避免只有一个声道有声时被平均掉 6 dB；float→int16 = ×32767 并限幅。
//  - 播放：同样 48 kHz float32 设备声道数，单声道复制到所有声道；环形缓冲空了写静音（不阻塞音频引擎）。
//  - 进程回环 / 回环：目标没出声时系统可能不送包，native 按墙钟补静音，保证 Java 每 10 ms 都能读到一帧。
// 只依赖 ole32（其余 API 用 GetProcAddress 取：Mmdevapi!ActivateAudioInterfaceAsync、avrt、ntdll!RtlGetVersion），
// 老系统上缺什么就返回错误而不是加载失败。
#define WIN32_LEAN_AND_MEAN
#define _CRT_SECURE_NO_WARNINGS
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#include <windows.h>
#include <objbase.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <audiopolicy.h>
#include <mmreg.h>
#include <stdio.h>
#include <string.h>
#include <math.h>

#define MM_API extern "C" __declspec(dllexport)
#define MM_LIB_VERSION 1

// ------------------------------------------------------------------ GUID（自己定义，避免依赖 uuid.lib / initguid 的差异）
static const GUID MM_CLSID_MMDeviceEnumerator = {0xBCDE0395, 0xE52F, 0x467C, {0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E}};
static const GUID MM_IID_IMMDeviceEnumerator = {0xA95664D2, 0x9614, 0x4F35, {0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6}};
static const GUID MM_IID_IAudioClient = {0x1CB9AD4C, 0xDBFA, 0x4c32, {0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2}};
static const GUID MM_IID_IAudioCaptureClient = {0xC8ADBD64, 0xE71E, 0x48a0, {0xA4, 0xDE, 0x18, 0x5C, 0x39, 0x5C, 0xD3, 0x17}};
static const GUID MM_IID_IAudioRenderClient = {0xF294ACFC, 0x3146, 0x4483, {0xA7, 0xBF, 0xAD, 0xDC, 0xA7, 0xC2, 0x60, 0xE2}};
static const GUID MM_IID_IAudioSessionManager2 = {0x77AA99A0, 0x1BD6, 0x484F, {0x8B, 0xC7, 0x2C, 0x65, 0x4C, 0x9A, 0x9B, 0x6F}};
static const GUID MM_IID_IAudioSessionControl2 = {0xbfb7ff88, 0x7239, 0x4fc9, {0x8f, 0xa2, 0x07, 0xc9, 0x50, 0xbe, 0x9c, 0x6d}};
static const GUID MM_IID_IAudioMeterInformation = {0xC02216F6, 0x8C67, 0x4B5B, {0x9D, 0x00, 0xD0, 0x08, 0xE7, 0x3E, 0x00, 0x64}};
static const GUID MM_IID_ICompletion = {0x41D949AB, 0x9862, 0x444A, {0x80, 0xF6, 0xC2, 0x61, 0x33, 0x4D, 0xA5, 0xEB}};
static const GUID MM_IID_IAgileObject = {0x94ea2b94, 0xe9cc, 0x49e0, {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
static const GUID MM_IID_IUnknown = {0x00000000, 0x0000, 0x0000, {0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID MM_SUBTYPE_FLOAT = {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
static const GUID MM_SUBTYPE_PCM = {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
static const PROPERTYKEY MM_PKEY_FriendlyName = {{0xa45c254e, 0xdf1c, 0x4efd, {0x80, 0x20, 0x67, 0xd1, 0x46, 0xa8, 0x50, 0xe0}}, 14};

// 进程回环激活参数（audioclientactivationparams.h，自己声明，旧 SDK / mingw 也能编）
enum MmActType { MM_ACT_DEFAULT = 0, MM_ACT_PROCESS_LOOPBACK = 1 };
enum MmLoopMode { MM_LOOP_INCLUDE_TREE = 0, MM_LOOP_EXCLUDE_TREE = 1 };
struct MmLoopParams { DWORD TargetProcessId; MmLoopMode ProcessLoopbackMode; };
struct MmActParams { MmActType ActivationType; MmLoopParams ProcessLoopbackParams; };

struct MmIMeter : public IUnknown { virtual HRESULT STDMETHODCALLTYPE GetPeakValue(float* p) = 0; };
struct MmIAsyncOp : public IUnknown { virtual HRESULT STDMETHODCALLTYPE GetActivateResult(HRESULT* hr, IUnknown** itf) = 0; };
struct MmICompletion : public IUnknown { virtual HRESULT STDMETHODCALLTYPE ActivateCompleted(MmIAsyncOp* op) = 0; };
typedef HRESULT(WINAPI* PFN_ActivateAsync)(LPCWSTR, REFIID, PROPVARIANT*, MmICompletion*, MmIAsyncOp**);

// ------------------------------------------------------------------ 工具

static void setErr(char* dst, int len, const char* fmt, ...) {
    if (!dst || len <= 0) return;
    va_list ap; va_start(ap, fmt); _vsnprintf(dst, (size_t)len - 1, fmt, ap); va_end(ap); dst[len - 1] = 0;
}

static int wToUtf8(const wchar_t* w, char* out, int len) {
    if (!w) { if (len > 0) out[0] = 0; return 0; }
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, out, len, NULL, NULL);
    if (n <= 0) { if (len > 0) out[0] = 0; return 0; }
    return n - 1;
}

static wchar_t* utf8ToW(const char* s) {
    if (!s) return NULL;
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    wchar_t* w = (wchar_t*)calloc((size_t)n + 1, sizeof(wchar_t));
    MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n);
    return w;
}

static DWORD winBuild() {
    typedef LONG(WINAPI * RtlGetVersionFn)(OSVERSIONINFOW*);
    HMODULE nt = GetModuleHandleW(L"ntdll.dll");
    RtlGetVersionFn f = nt ? (RtlGetVersionFn)(void*)GetProcAddress(nt, "RtlGetVersion") : NULL;
    if (!f) return 0;
    OSVERSIONINFOW v; memset(&v, 0, sizeof(v)); v.dwOSVersionInfoSize = sizeof(v);
    return f(&v) == 0 ? v.dwBuildNumber : 0;
}

static HANDLE mmcssEnter() {
    typedef HANDLE(WINAPI * AvSetFn)(LPCWSTR, LPDWORD);
    HMODULE av = LoadLibraryW(L"avrt.dll");
    AvSetFn f = av ? (AvSetFn)(void*)GetProcAddress(av, "AvSetMmThreadCharacteristicsW") : NULL;
    DWORD idx = 0;
    HANDLE h = f ? f(L"Pro Audio", &idx) : NULL;
    if (!h) SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);
    return h;
}

template <class T> static void rel(T*& p) { if (p) { p->Release(); p = NULL; } }

// ------------------------------------------------------------------ 环形缓冲（单声道 int16）

struct Ring {
    short* d = NULL; int cap = 0, r = 0, n = 0;
    CRITICAL_SECTION cs; CONDITION_VARIABLE cv;
    void init(int c) { d = (short*)calloc((size_t)c, sizeof(short)); cap = c; InitializeCriticalSection(&cs); InitializeConditionVariable(&cv); }
    void destroy() { DeleteCriticalSection(&cs); free(d); d = NULL; }
    // 写：满了就覆盖最旧的（采集侧用）
    void pushOverwrite(const short* s, int k) {
        EnterCriticalSection(&cs);
        for (int i = 0; i < k; i++) { d[(r + n) % cap] = s[i]; if (n < cap) n++; else r = (r + 1) % cap; }
        LeaveCriticalSection(&cs); WakeAllConditionVariable(&cv);
    }
};

// ------------------------------------------------------------------ 流

struct Stream {
    int capture = 1;          // 1 采集 0 播放
    int loopback = 0;         // 进程回环
    DWORD pid = 0; int excludeTree = 0;
    wchar_t* devName = NULL;  // NULL = 默认设备
    int ringMs = 200;
    Ring ring;
    HANDLE thread = NULL, ready = NULL, ev = NULL;
    volatile LONG stop = 0, dead = 0, ok = 0;
    char err[512] = {0};
    char info[256] = {0};
    int ch = 2; int isFloat = 1; int bits = 32;
    volatile LONG64 frames = 0, underruns = 0;
};

static HRESULT findDevice(IMMDeviceEnumerator* en, EDataFlow flow, const wchar_t* name, IMMDevice** out) {
    if (!name || !name[0]) return en->GetDefaultAudioEndpoint(flow, eConsole, out);
    IMMDeviceCollection* col = NULL;
    HRESULT hr = en->EnumAudioEndpoints(flow, DEVICE_STATE_ACTIVE, &col);
    if (FAILED(hr)) return hr;
    UINT cnt = 0; col->GetCount(&cnt);
    IMMDevice* best = NULL; int bestScore = 0;
    for (UINT i = 0; i < cnt; i++) {
        IMMDevice* d = NULL; if (FAILED(col->Item(i, &d))) continue;
        IPropertyStore* ps = NULL;
        if (SUCCEEDED(d->OpenPropertyStore(STGM_READ, &ps))) {
            PROPVARIANT v; memset(&v, 0, sizeof(v));
            if (SUCCEEDED(ps->GetValue(MM_PKEY_FriendlyName, &v)) && v.vt == VT_LPWSTR && v.pwszVal) {
                int score = 0;
                if (_wcsicmp(v.pwszVal, name) == 0) score = 3;
                else if (_wcsnicmp(v.pwszVal, name, wcslen(name)) == 0) score = 2;      // javax.sound 名字可能被截断
                else if (wcsstr(v.pwszVal, name)) score = 1;
                if (score > bestScore) { rel(best); best = d; d->AddRef(); bestScore = score; }
            }
            PropVariantClear(&v); ps->Release();
        }
        d->Release();
    }
    col->Release();
    if (!best) return E_INVALIDARG;
    *out = best; return S_OK;
}

// 进程回环激活回调（IAgileObject：可在任意线程完成）
struct Completion : public MmICompletion {
    LONG refs = 1; HANDLE done; HRESULT hr = E_FAIL; IAudioClient* client = NULL;
    Completion() { done = CreateEventW(NULL, TRUE, FALSE, NULL); }
    virtual ~Completion() { CloseHandle(done); }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** p) override {
        if (IsEqualGUID(riid, MM_IID_IUnknown) || IsEqualGUID(riid, MM_IID_ICompletion) || IsEqualGUID(riid, MM_IID_IAgileObject)) { *p = this; AddRef(); return S_OK; }
        *p = NULL; return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return (ULONG)InterlockedIncrement(&refs); }
    ULONG STDMETHODCALLTYPE Release() override { LONG r = InterlockedDecrement(&refs); if (r == 0) delete this; return (ULONG)r; }
    HRESULT STDMETHODCALLTYPE ActivateCompleted(MmIAsyncOp* op) override {
        HRESULT ahr = E_FAIL; IUnknown* u = NULL;
        HRESULT r = op->GetActivateResult(&ahr, &u);
        if (SUCCEEDED(r) && SUCCEEDED(ahr) && u) { hr = u->QueryInterface(MM_IID_IAudioClient, (void**)&client); }
        else hr = FAILED(r) ? r : ahr;
        if (u) u->Release();
        SetEvent(done); return S_OK;
    }
};

static HRESULT activateProcessLoopback(DWORD pid, int exclude, IAudioClient** out, char* err, int errLen) {
    DWORD b = winBuild();
    if (b && b < 19041) { setErr(err, errLen, "process loopback needs Windows 10 2004 (build 19041)+, this is build %lu", (unsigned long)b); return E_NOTIMPL; }
    HMODULE m = LoadLibraryW(L"Mmdevapi.dll");
    PFN_ActivateAsync act = m ? (PFN_ActivateAsync)(void*)GetProcAddress(m, "ActivateAudioInterfaceAsync") : NULL;
    if (!act) { setErr(err, errLen, "ActivateAudioInterfaceAsync not available"); return E_NOTIMPL; }
    MmActParams p; memset(&p, 0, sizeof(p));
    p.ActivationType = MM_ACT_PROCESS_LOOPBACK;
    p.ProcessLoopbackParams.TargetProcessId = pid;
    p.ProcessLoopbackParams.ProcessLoopbackMode = exclude ? MM_LOOP_EXCLUDE_TREE : MM_LOOP_INCLUDE_TREE;
    PROPVARIANT pv; memset(&pv, 0, sizeof(pv));
    pv.vt = VT_BLOB; pv.blob.cbSize = sizeof(p); pv.blob.pBlobData = (BYTE*)&p;
    Completion* c = new Completion();
    MmIAsyncOp* op = NULL;
    HRESULT hr = act(L"VAD\\Process_Loopback", MM_IID_IAudioClient, &pv, c, &op);
    if (SUCCEEDED(hr)) {
        if (WaitForSingleObject(c->done, 5000) != WAIT_OBJECT_0) hr = HRESULT_FROM_WIN32(ERROR_TIMEOUT);
        else { hr = c->hr; if (SUCCEEDED(hr)) { *out = c->client; c->client = NULL; } }
    }
    if (FAILED(hr)) setErr(err, errLen, "process loopback activation failed (pid %lu): 0x%08lx", (unsigned long)pid, (unsigned long)hr);
    if (c->client) c->client->Release();
    if (op) op->Release();
    c->Release();
    return hr;
}

// 一个包 → 单声道 int16（采集）。只平均「有信号」的声道
static void downmix(const BYTE* data, UINT32 frames, int ch, int isFloat, int bits, short* out) {
    double e[16] = {0}; int C = ch > 16 ? 16 : ch;
    for (UINT32 i = 0; i < frames; i++) for (int c = 0; c < C; c++) {
        double v;
        if (isFloat) v = ((const float*)data)[i * ch + c];
        else if (bits == 16) v = ((const short*)data)[i * ch + c] / 32768.0;
        else v = ((const int*)data)[i * ch + c] / 2147483648.0;
        e[c] += v * v;
    }
    double mx = 0; for (int c = 0; c < C; c++) if (e[c] > mx) mx = e[c];
    int use[16]; int nu = 0;
    for (int c = 0; c < C; c++) if (mx <= 0 || e[c] >= mx * 0.01) use[nu++] = c;   // 最响声道 -20 dB 以内
    for (UINT32 i = 0; i < frames; i++) {
        double acc = 0;
        for (int k = 0; k < nu; k++) {
            int c = use[k];
            if (isFloat) acc += ((const float*)data)[i * ch + c];
            else if (bits == 16) acc += ((const short*)data)[i * ch + c] / 32768.0;
            else acc += ((const int*)data)[i * ch + c] / 2147483648.0;
        }
        double v = acc / (nu > 0 ? nu : 1) * 32767.0;
        if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
        out[i] = (short)lrint(v);
    }
}

static void fillFormat(WAVEFORMATEXTENSIBLE* f, int ch, DWORD mask, int isFloat, int bits) {
    memset(f, 0, sizeof(*f));
    f->Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    f->Format.nChannels = (WORD)ch;
    f->Format.nSamplesPerSec = 48000;
    f->Format.wBitsPerSample = (WORD)bits;
    f->Format.nBlockAlign = (WORD)(ch * bits / 8);
    f->Format.nAvgBytesPerSec = 48000 * f->Format.nBlockAlign;
    f->Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    f->Samples.wValidBitsPerSample = (WORD)bits;
    f->dwChannelMask = mask ? mask : (ch == 1 ? SPEAKER_FRONT_CENTER : ch == 2 ? (SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT) : 0);
    f->SubFormat = isFloat ? MM_SUBTYPE_FLOAT : MM_SUBTYPE_PCM;
}

static DWORD WINAPI streamThread(LPVOID arg) {
    Stream* s = (Stream*)arg;
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    bool coOk = SUCCEEDED(hr);
    HANDLE mm = NULL;
    IMMDeviceEnumerator* en = NULL; IMMDevice* dev = NULL; IAudioClient* ac = NULL;
    IAudioCaptureClient* cc = NULL; IAudioRenderClient* rc = NULL;
    UINT32 bufFrames = 0;
    WAVEFORMATEXTENSIBLE fmt;
    short* tmp = NULL; int tmpCap = 0;
    LARGE_INTEGER qf, t0; QueryPerformanceFrequency(&qf);

    do {
        if (s->loopback) {
            hr = activateProcessLoopback(s->pid, s->excludeTree, &ac, s->err, sizeof(s->err));
            if (FAILED(hr)) break;
            s->ch = 2; s->isFloat = 0; s->bits = 16;
            fillFormat(&fmt, 2, 0, 0, 16);
            fmt.Format.wFormatTag = WAVE_FORMAT_PCM; fmt.Format.cbSize = 0;
            hr = ac->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
                200000, 0, &fmt.Format, NULL);
            if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "process loopback Initialize failed: 0x%08lx", (unsigned long)hr); break; }
        } else {
            hr = CoCreateInstance(MM_CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL, MM_IID_IMMDeviceEnumerator, (void**)&en);
            if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "MMDeviceEnumerator failed: 0x%08lx", (unsigned long)hr); break; }
            hr = findDevice(en, s->capture ? eCapture : eRender, s->devName, &dev);
            if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "audio endpoint not found: 0x%08lx", (unsigned long)hr); break; }
            hr = dev->Activate(MM_IID_IAudioClient, CLSCTX_ALL, NULL, (void**)&ac);
            if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "IAudioClient activate failed: 0x%08lx", (unsigned long)hr); break; }
            WAVEFORMATEX* mix = NULL;
            int ch = 2; DWORD mask = 0;
            if (SUCCEEDED(ac->GetMixFormat(&mix)) && mix) {
                ch = mix->nChannels;
                if (mix->wFormatTag == WAVE_FORMAT_EXTENSIBLE) mask = ((WAVEFORMATEXTENSIBLE*)mix)->dwChannelMask;
                CoTaskMemFree(mix);
            }
            if (ch < 1 || ch > 16) { ch = 2; mask = 0; }
            DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
            REFERENCE_TIME dur = s->capture ? 200000 : 300000;   // 采集 20 ms / 播放 30 ms 设备缓冲
            fillFormat(&fmt, ch, mask, 1, 32);
            hr = ac->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, dur, 0, &fmt.Format, NULL);
            if (FAILED(hr) && ch != 2) {   // 多声道不认：退到立体声
                rel(ac); hr = dev->Activate(MM_IID_IAudioClient, CLSCTX_ALL, NULL, (void**)&ac);
                if (SUCCEEDED(hr)) { ch = 2; fillFormat(&fmt, 2, 0, 1, 32); hr = ac->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, dur, 0, &fmt.Format, NULL); }
            }
            if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "IAudioClient Initialize failed: 0x%08lx", (unsigned long)hr); break; }
            s->ch = ch; s->isFloat = 1; s->bits = 32;
        }
        s->ev = CreateEventW(NULL, FALSE, FALSE, NULL);
        hr = ac->SetEventHandle(s->ev);
        if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "SetEventHandle failed: 0x%08lx", (unsigned long)hr); break; }
        ac->GetBufferSize(&bufFrames);
        if (s->capture) hr = ac->GetService(MM_IID_IAudioCaptureClient, (void**)&cc);
        else hr = ac->GetService(MM_IID_IAudioRenderClient, (void**)&rc);
        if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "GetService failed: 0x%08lx", (unsigned long)hr); break; }
        REFERENCE_TIME defP = 0, minP = 0; ac->GetDevicePeriod(&defP, &minP);
        _snprintf(s->info, sizeof(s->info) - 1, "%s %s %dch %s48000Hz buffer %u frames period %.1f ms",
            s->capture ? "capture" : "render", s->loopback ? "process-loopback" : "wasapi-shared", s->ch, s->isFloat ? "float32 " : "int16 ", bufFrames, defP / 10000.0);
        if (rc) {   // 先填一段静音再启动，避免启动瞬间欠载
            BYTE* p = NULL;
            if (SUCCEEDED(rc->GetBuffer(bufFrames, &p))) rc->ReleaseBuffer(bufFrames, AUDCLNT_BUFFERFLAGS_SILENT);
        }
        hr = ac->Start();
        if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "Start failed: 0x%08lx", (unsigned long)hr); break; }
        InterlockedExchange(&s->ok, 1);
    } while (0);

    SetEvent(s->ready);
    if (!s->ok) { InterlockedExchange(&s->dead, 1); goto cleanup; }

    mm = mmcssEnter();
    QueryPerformanceCounter(&t0);
    tmpCap = 48000; tmp = (short*)calloc((size_t)tmpCap, sizeof(short));
    while (!s->stop) {
        DWORD w = WaitForSingleObject(s->ev, 100);
        if (s->stop) break;
        if (s->capture) {
            for (;;) {
                UINT32 pk = 0;
                hr = cc->GetNextPacketSize(&pk);
                if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "capture device lost: 0x%08lx", (unsigned long)hr); InterlockedExchange(&s->dead, 1); break; }
                if (pk == 0) break;
                BYTE* data = NULL; UINT32 fr = 0; DWORD fl = 0;
                hr = cc->GetBuffer(&data, &fr, &fl, NULL, NULL);
                if (FAILED(hr)) { InterlockedExchange(&s->dead, 1); break; }
                if ((int)fr > tmpCap) { free(tmp); tmpCap = (int)fr; tmp = (short*)calloc((size_t)tmpCap, sizeof(short)); }
                if (fl & AUDCLNT_BUFFERFLAGS_SILENT) memset(tmp, 0, fr * sizeof(short));
                else downmix(data, fr, s->ch, s->isFloat, s->bits, tmp);
                cc->ReleaseBuffer(fr);
                s->ring.pushOverwrite(tmp, (int)fr);
                InterlockedExchangeAdd64(&s->frames, fr);
            }
            if (s->dead) break;
            if (s->loopback) {
                // 目标没出声时系统不送包：按墙钟补静音（落后 > 30 ms 时补到只差 10 ms）
                LARGE_INTEGER now; QueryPerformanceCounter(&now);
                LONG64 want = (LONG64)((now.QuadPart - t0.QuadPart) * 48000.0 / qf.QuadPart);
                LONG64 lag = want - s->frames;
                if (lag > 1440) {
                    int k = (int)(lag - 480); if (k > tmpCap) k = tmpCap;
                    memset(tmp, 0, (size_t)k * sizeof(short));
                    s->ring.pushOverwrite(tmp, k);
                    InterlockedExchangeAdd64(&s->frames, k);
                }
            }
        } else {
            UINT32 pad = 0;
            hr = ac->GetCurrentPadding(&pad);
            if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "render device lost: 0x%08lx", (unsigned long)hr); InterlockedExchange(&s->dead, 1); break; }
            UINT32 avail = bufFrames - pad;
            if (avail == 0) continue;
            BYTE* p = NULL;
            hr = rc->GetBuffer(avail, &p);
            if (FAILED(hr)) { setErr(s->err, sizeof(s->err), "render GetBuffer failed: 0x%08lx", (unsigned long)hr); InterlockedExchange(&s->dead, 1); break; }
            float* o = (float*)p;
            EnterCriticalSection(&s->ring.cs);
            UINT32 k = (UINT32)s->ring.n < avail ? (UINT32)s->ring.n : avail;
            for (UINT32 i = 0; i < k; i++) {
                float v = s->ring.d[s->ring.r] / 32768.0f;
                s->ring.r = (s->ring.r + 1) % s->ring.cap;
                for (int c = 0; c < s->ch; c++) o[i * s->ch + c] = v;
            }
            s->ring.n -= (int)k;
            LeaveCriticalSection(&s->ring.cs);
            WakeAllConditionVariable(&s->ring.cv);
            for (UINT32 i = k; i < avail; i++) for (int c = 0; c < s->ch; c++) o[i * s->ch + c] = 0.0f;
            if (k < avail && s->frames > 0) InterlockedIncrement64(&s->underruns);
            rc->ReleaseBuffer(avail, 0);
            InterlockedExchangeAdd64(&s->frames, k);
        }
        (void)w;
    }
    ac->Stop();

cleanup:
    InterlockedExchange(&s->dead, 1);
    WakeAllConditionVariable(&s->ring.cv);
    free(tmp);
    if (mm) {
        typedef BOOL(WINAPI * AvRevFn)(HANDLE);
        HMODULE av = GetModuleHandleW(L"avrt.dll");
        AvRevFn f = av ? (AvRevFn)(void*)GetProcAddress(av, "AvRevertMmThreadCharacteristics") : NULL;
        if (f) f(mm);
    }
    rel(cc); rel(rc); rel(ac); rel(dev); rel(en);
    if (coOk) CoUninitialize();
    return 0;
}

static Stream* openStream(Stream* s, int ringMs, char* err, int errLen) {
    s->ringMs = ringMs < 10 ? 10 : ringMs > 2000 ? 2000 : ringMs;
    s->ring.init(48 * s->ringMs);
    s->ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    s->thread = CreateThread(NULL, 0, streamThread, s, 0, NULL);
    if (!s->thread) { setErr(err, errLen, "CreateThread failed"); s->ring.destroy(); CloseHandle(s->ready); free(s->devName); delete s; return NULL; }
    WaitForSingleObject(s->ready, 10000);
    if (!s->ok) {
        setErr(err, errLen, "%s", s->err[0] ? s->err : "open timeout");
        InterlockedExchange(&s->stop, 1);
        WaitForSingleObject(s->thread, 3000);
        CloseHandle(s->thread); CloseHandle(s->ready); if (s->ev) CloseHandle(s->ev);
        s->ring.destroy(); free(s->devName); delete s;
        return NULL;
    }
    return s;
}

// ================================================================== 导出

MM_API int mm_version() { return MM_LIB_VERSION; }
MM_API int mm_win_build() { return (int)winBuild(); }

/** 打开设备流。capture=1 录音 0 播放；name = UTF-8 端点名（空 = 系统默认）；ringMs 环形缓冲（播放侧 = 最大排队） */
MM_API void* mm_open_device(int capture, const char* nameUtf8, int ringMs, char* err, int errLen) {
    Stream* s = new Stream();
    s->capture = capture ? 1 : 0;
    s->devName = (nameUtf8 && nameUtf8[0]) ? utf8ToW(nameUtf8) : NULL;
    return openStream(s, ringMs, err, errLen);
}

/** 按进程抓声音：exclude=0 只抓该进程树，1 抓除该进程树以外的所有声音 */
MM_API void* mm_open_process(int pid, int exclude, int ringMs, char* err, int errLen) {
    Stream* s = new Stream();
    s->capture = 1; s->loopback = 1; s->pid = (DWORD)pid; s->excludeTree = exclude ? 1 : 0;
    return openStream(s, ringMs, err, errLen);
}

/** 读 n 个样本（48 kHz 单声道 int16），最多等 timeoutMs；返回读到的个数，流已失效且没数据时返回 -1 */
MM_API int mm_read(void* h, short* buf, int n, int timeoutMs) {
    Stream* s = (Stream*)h; if (!s || n <= 0) return 0;
    ULONGLONG end = GetTickCount64() + (ULONGLONG)(timeoutMs < 0 ? 0 : timeoutMs);
    EnterCriticalSection(&s->ring.cs);
    while (s->ring.n < n && !s->dead && !s->stop) {
        ULONGLONG now = GetTickCount64(); if (now >= end) break;
        SleepConditionVariableCS(&s->ring.cv, &s->ring.cs, (DWORD)(end - now));
    }
    int k = s->ring.n < n ? s->ring.n : n;
    for (int i = 0; i < k; i++) { buf[i] = s->ring.d[s->ring.r]; s->ring.r = (s->ring.r + 1) % s->ring.cap; }
    s->ring.n -= k;
    int dead = s->dead;
    LeaveCriticalSection(&s->ring.cs);
    return (k == 0 && dead) ? -1 : k;
}

/** 写 n 个样本到播放环形缓冲，满了最多等 timeoutMs；返回写入个数，流失效返回 -1 */
MM_API int mm_write(void* h, const short* buf, int n, int timeoutMs) {
    Stream* s = (Stream*)h; if (!s || n <= 0) return 0;
    ULONGLONG end = GetTickCount64() + (ULONGLONG)(timeoutMs < 0 ? 0 : timeoutMs);
    int done = 0;
    EnterCriticalSection(&s->ring.cs);
    while (done < n && !s->dead && !s->stop) {
        int space = s->ring.cap - s->ring.n;
        if (space <= 0) {
            ULONGLONG now = GetTickCount64(); if (now >= end) break;
            SleepConditionVariableCS(&s->ring.cv, &s->ring.cs, (DWORD)(end - now));
            continue;
        }
        int k = n - done < space ? n - done : space;
        for (int i = 0; i < k; i++) s->ring.d[(s->ring.r + s->ring.n + i) % s->ring.cap] = buf[done + i];
        s->ring.n += k; done += k;
    }
    int dead = s->dead;
    LeaveCriticalSection(&s->ring.cs);
    return (done == 0 && dead) ? -1 : done;
}

/** 环形缓冲里的样本数（采集 = 积压，播放 = 排队） */
MM_API int mm_available(void* h) {
    Stream* s = (Stream*)h; if (!s) return 0;
    EnterCriticalSection(&s->ring.cs); int n = s->ring.n; LeaveCriticalSection(&s->ring.cs); return n;
}

/** 丢掉最旧的 n 个样本（采集积压控制） */
MM_API int mm_skip(void* h, int n) {
    Stream* s = (Stream*)h; if (!s || n <= 0) return 0;
    EnterCriticalSection(&s->ring.cs);
    int k = s->ring.n < n ? s->ring.n : n; s->ring.r = (s->ring.r + k) % s->ring.cap; s->ring.n -= k;
    LeaveCriticalSection(&s->ring.cs); WakeAllConditionVariable(&s->ring.cv);
    return k;
}

/** 1 = 流还活着；0 = 设备拔出/出错（错误见 mm_info） */
MM_API int mm_alive(void* h) { Stream* s = (Stream*)h; return (s && !s->dead) ? 1 : 0; }

/** 流信息：格式/周期 + 欠载次数 + 错误（UTF-8） */
MM_API int mm_info(void* h, char* buf, int len) {
    Stream* s = (Stream*)h; if (!s || !buf || len <= 0) return 0;
    _snprintf(buf, (size_t)len - 1, "%s underruns %lld%s%s", s->info, (long long)s->underruns, s->err[0] ? " error: " : "", s->err);
    buf[len - 1] = 0; return (int)strlen(buf);
}

MM_API void mm_close(void* h) {
    Stream* s = (Stream*)h; if (!s) return;
    InterlockedExchange(&s->stop, 1);
    if (s->ev) SetEvent(s->ev);
    WakeAllConditionVariable(&s->ring.cv);
    if (WaitForSingleObject(s->thread, 2000) != WAIT_OBJECT_0) return;   // 卡住就泄漏，不冒崩溃风险
    CloseHandle(s->thread); CloseHandle(s->ready); if (s->ev) CloseHandle(s->ev);
    s->ring.destroy(); free(s->devName); delete s;
}

// ------------------------------------------------------------------ 列表（在独立 MTA 线程里做，调用线程是什么 COM 模式都行）

struct ListJob { int kind; int capture; char* out; int len; int n; };

static void appendf(ListJob* j, const char* fmt, ...) {
    if (j->n >= j->len - 1) return;
    va_list ap; va_start(ap, fmt);
    int k = _vsnprintf(j->out + j->n, (size_t)(j->len - 1 - j->n), fmt, ap);
    va_end(ap);
    if (k < 0) j->n = j->len - 1; else j->n += k;
    j->out[j->n] = 0;
}

static void sanitize(char* s) { for (; *s; s++) if (*s == '\t' || *s == '\n' || *s == '\r') *s = ' '; }

static void listEndpoints(ListJob* j, IMMDeviceEnumerator* en) {
    EDataFlow flow = j->capture ? eCapture : eRender;
    wchar_t* defId = NULL;
    IMMDevice* def = NULL;
    if (SUCCEEDED(en->GetDefaultAudioEndpoint(flow, eConsole, &def))) { def->GetId(&defId); def->Release(); }
    IMMDeviceCollection* col = NULL;
    if (SUCCEEDED(en->EnumAudioEndpoints(flow, DEVICE_STATE_ACTIVE, &col))) {
        UINT cnt = 0; col->GetCount(&cnt);
        for (UINT i = 0; i < cnt; i++) {
            IMMDevice* d = NULL; if (FAILED(col->Item(i, &d))) continue;
            wchar_t* id = NULL; d->GetId(&id);
            IPropertyStore* ps = NULL; char name[512] = {0};
            if (SUCCEEDED(d->OpenPropertyStore(STGM_READ, &ps))) {
                PROPVARIANT v; memset(&v, 0, sizeof(v));
                if (SUCCEEDED(ps->GetValue(MM_PKEY_FriendlyName, &v)) && v.vt == VT_LPWSTR) wToUtf8(v.pwszVal, name, sizeof(name));
                PropVariantClear(&v); ps->Release();
            }
            sanitize(name);
            appendf(j, "%d\t%s\n", (id && defId && wcscmp(id, defId) == 0) ? 1 : 0, name);
            if (id) CoTaskMemFree(id);
            d->Release();
        }
        col->Release();
    }
    if (defId) CoTaskMemFree(defId);
}

struct Sess { DWORD pid; int active; float peak; };

static void listSessions(ListJob* j, IMMDeviceEnumerator* en) {
    Sess ss[256]; int ns = 0;
    DWORD self = GetCurrentProcessId();
    IMMDeviceCollection* col = NULL;
    if (FAILED(en->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &col))) return;
    UINT cnt = 0; col->GetCount(&cnt);
    for (UINT i = 0; i < cnt; i++) {
        IMMDevice* d = NULL; if (FAILED(col->Item(i, &d))) continue;
        IAudioSessionManager2* mgr = NULL;
        if (SUCCEEDED(d->Activate(MM_IID_IAudioSessionManager2, CLSCTX_ALL, NULL, (void**)&mgr))) {
            IAudioSessionEnumerator* se = NULL;
            if (SUCCEEDED(mgr->GetSessionEnumerator(&se))) {
                int sc = 0; se->GetCount(&sc);
                for (int k = 0; k < sc; k++) {
                    IAudioSessionControl* c = NULL; if (FAILED(se->GetSession(k, &c))) continue;
                    IAudioSessionControl2* c2 = NULL;
                    if (SUCCEEDED(c->QueryInterface(MM_IID_IAudioSessionControl2, (void**)&c2))) {
                        DWORD pid = 0; c2->GetProcessId(&pid);
                        if (pid != 0 && pid != self && c2->IsSystemSoundsSession() != S_OK) {
                            AudioSessionState st = AudioSessionStateInactive; c2->GetState(&st);
                            float peak = 0; MmIMeter* m = NULL;
                            if (SUCCEEDED(c->QueryInterface(MM_IID_IAudioMeterInformation, (void**)&m))) { m->GetPeakValue(&peak); m->Release(); }
                            int f = -1; for (int q = 0; q < ns; q++) if (ss[q].pid == pid) f = q;
                            if (f < 0 && ns < 256) { f = ns++; ss[f].pid = pid; ss[f].active = 0; ss[f].peak = 0; }
                            if (f >= 0) { if (st == AudioSessionStateActive) ss[f].active = 1; if (peak > ss[f].peak) ss[f].peak = peak; }
                        }
                        c2->Release();
                    }
                    c->Release();
                }
                se->Release();
            }
            mgr->Release();
        }
        d->Release();
    }
    col->Release();
    for (int q = 0; q < ns; q++) {
        char exe[600] = {0};
        HANDLE p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, ss[q].pid);
        if (p) {
            wchar_t path[MAX_PATH * 2]; DWORD sz = MAX_PATH * 2;
            if (QueryFullProcessImageNameW(p, 0, path, &sz)) wToUtf8(path, exe, sizeof(exe));
            CloseHandle(p);
        }
        sanitize(exe);
        appendf(j, "%lu\t%d\t%.4f\t%s\n", (unsigned long)ss[q].pid, ss[q].active, ss[q].peak, exe);
    }
}

static DWORD WINAPI listThread(LPVOID arg) {
    ListJob* j = (ListJob*)arg;
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    IMMDeviceEnumerator* en = NULL;
    if (SUCCEEDED(CoCreateInstance(MM_CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL, MM_IID_IMMDeviceEnumerator, (void**)&en))) {
        if (j->kind == 0) listEndpoints(j, en); else listSessions(j, en);
        en->Release();
    } else j->n = -1;
    if (SUCCEEDED(hr)) CoUninitialize();
    return 0;
}

static int runList(int kind, int capture, char* buf, int len) {
    if (!buf || len <= 1) return -1;
    buf[0] = 0;
    ListJob j = {kind, capture, buf, len, 0};
    HANDLE t = CreateThread(NULL, 0, listThread, &j, 0, NULL);
    if (!t) return -1;
    if (WaitForSingleObject(t, 5000) != WAIT_OBJECT_0) { CloseHandle(t); return -1; }   // 超时：不再读 j（线程可能还在写 buf，调用方的 buf 要留到下次调用）
    CloseHandle(t);
    return j.n;
}

/** 端点列表（UTF-8，每行「是否默认\t名字」），返回字节数，失败 -1 */
MM_API int mm_list_endpoints(int capture, char* buf, int len) { return runList(0, capture, buf, len); }

/** 正在用声音的进程（UTF-8，每行「pid\t是否在出声\t峰值0..1\t程序完整路径」），返回字节数，失败 -1 */
MM_API int mm_list_sessions(char* buf, int len) { return runList(1, 0, buf, len); }
