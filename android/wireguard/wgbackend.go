package main

/*
#include <jni.h>
#include <stdlib.h>
*/
import "C"
import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"sync"
	"unsafe"

	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/ipc"
	"golang.zx2c4.com/wireguard/tun"
)

var (
	devices   = make(map[string]*device.Device)
	tunDevs   = make(map[string]tun.Device)
	devicesMu sync.Mutex
)

type androidTun struct {
	fd      int
	name    string
	mtu     int
	events  chan tun.TUNEvent
	errors  chan error
	closed  bool
}

func (t *androidTun) File() *os.File           { return os.NewFile(uintptr(t.fd), t.name) }
func (t *androidTun) Name() string              { return t.name }
func (t *androidTun) Events() <-chan tun.TUNEvent { return t.events }
func (t *androidTun) Errors() <-chan error      { return t.errors }
func (t *androidTun) Flush() error              { return nil }
func (t *androidTun) MTU() (int, error)         { return t.mtu, nil }
func (t *androidTun) BatchSize() int            { return 1 }
func (t *androidTun) Close() error {
	if t.closed { return nil }
	t.closed = true
	return os.NewFile(uintptr(t.fd), t.name).Close()
}

//export wgTurnOn
func wgTurnOn(env *C.JNIEnv, cls C.jclass, jifname C.jstring, jtunFd C.jint, jsettings C.jstring) C.jint {
	ifname := C.GoString((*C.char)(unsafe.Pointer(C.GetStringUTFChars(env, jifname, nil))))
	settings := C.GoString((*C.char)(unsafe.Pointer(C.GetStringUTFChars(env, jsettings, nil))))
	tunFd := int(jtunFd)

	t := &androidTun{fd: tunFd, name: ifname, mtu: 1420,
		events: make(chan tun.TUNEvent, 10), errors: make(chan error, 10)}
	dev := device.NewDevice(tun.Device(t), device.NewLogger(device.LogLevelError, ""))

	uapi, err := ipc.UAPIListen(ifname)
	if err != nil {
		dev.Close()
		return C.jint(1)
	}

	err = dev.IpcSetOperation(bufio.NewReader(strings.NewReader(settings)))
	if err != nil {
		uapi.Close()
		dev.Close()
		return C.jint(2)
	}

	if err := dev.Up(); err != nil {
		uapi.Close()
		dev.Close()
		fmt.Fprintf(os.Stderr, "Device up failed: %v\n", err)
		return C.jint(3)
	}

	go func() {
		for {
			conn, err := uapi.Accept()
			if err != nil {
				return
			}
			go dev.IpcHandle(conn)
		}
	}()

	devicesMu.Lock()
	devices[ifname] = dev
	tunDevs[ifname] = tun.Device(t)
	devicesMu.Unlock()

	return C.jint(0)
}

//export wgTurnOff
func wgTurnOff(env *C.JNIEnv, cls C.jclass, jifname C.jstring) {
	ifname := C.GoString((*C.char)(unsafe.Pointer(C.GetStringUTFChars(env, jifname, nil))))

	devicesMu.Lock()
	defer devicesMu.Unlock()

	if dev, ok := devices[ifname]; ok {
		dev.Close()
		delete(devices, ifname)
	}
	if t, ok := tunDevs[ifname]; ok {
		t.Close()
		delete(tunDevs, ifname)
	}
}

//export wgGetSocketV4
func wgGetSocketV4(env *C.JNIEnv, cls C.jclass, jifname C.jstring) C.jint {
	// Returns the socket fd for IPv4 — not directly available via device API
	// for raw sockets we return -1 (not supported in non-root mode)
	return C.jint(-1)
}

//export wgGetSocketV6
func wgGetSocketV6(env *C.JNIEnv, cls C.jclass, jifname C.jstring) C.jint {
	return C.jint(-1)
}

//export wgVersion
func wgVersion(env *C.JNIEnv, cls C.jclass) C.jstring {
	return C.CString("1.0.0")
}

func main() {}
