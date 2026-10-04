package service

import (
	"testing"

	"github.com/veritasvpn/services/wg-manager/internal/model"
)

func TestMergeStoredDeviceMetadataKeepsNameWhenReconnectOmitsIt(t *testing.T) {
	previous := &model.Peer{
		DeviceName:      "Office PC",
		DevicePlatform:  "Linux",
		DeviceModel:     "framework",
		DeviceOSVersion: "Ubuntu 24.04",
		ClientVersion:   "0.2.82",
	}

	name, platform, deviceModel, osVersion, clientVersion := mergeStoredDeviceMetadata(
		"", "Linux", "framework", "Ubuntu 24.04", "0.2.82", previous,
	)
	if name != "Office PC" {
		t.Fatalf("stored name was wiped, got %q", name)
	}
	if platform != "Linux" || deviceModel != "framework" || osVersion != "Ubuntu 24.04" || clientVersion != "0.2.82" {
		t.Fatalf("unexpected metadata: %q %q %q %q", platform, deviceModel, osVersion, clientVersion)
	}
}

func TestMergeStoredDeviceMetadataKeepsEveryBlankField(t *testing.T) {
	previous := &model.Peer{
		DeviceName:      "Living room",
		DevicePlatform:  "Android",
		DeviceModel:     "Google Pixel",
		DeviceOSVersion: "Android 15",
		ClientVersion:   "0.2.82",
	}

	name, platform, deviceModel, osVersion, clientVersion := mergeStoredDeviceMetadata(
		"  ", "", "", "", "", previous,
	)
	if name != "Living room" || platform != "Android" || deviceModel != "Google Pixel" || osVersion != "Android 15" || clientVersion != "0.2.82" {
		t.Fatalf("blank reconnect wiped stored metadata: %q %q %q %q %q", name, platform, deviceModel, osVersion, clientVersion)
	}
}

func TestMergeStoredDeviceMetadataUsesIncomingWhenPresent(t *testing.T) {
	previous := &model.Peer{DeviceName: "Office PC", DevicePlatform: ""}

	name, platform, _, _, _ := mergeStoredDeviceMetadata(
		"Office PC", "Linux", "framework", "", "", previous,
	)
	if name != "Office PC" {
		t.Fatalf("name = %q", name)
	}
	if platform != "Linux" {
		t.Fatalf("platform = %q, want Linux", platform)
	}
}

func TestMergeStoredDeviceMetadataWithoutHistory(t *testing.T) {
	name, platform, deviceModel, osVersion, clientVersion := mergeStoredDeviceMetadata(
		"", "Linux", "framework", "Ubuntu 24.04", "0.2.82", nil,
	)
	if name != "" || platform != "Linux" || deviceModel != "framework" || osVersion != "Ubuntu 24.04" || clientVersion != "0.2.82" {
		t.Fatalf("got %q %q %q %q %q", name, platform, deviceModel, osVersion, clientVersion)
	}
}
