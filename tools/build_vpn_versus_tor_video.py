from pathlib import Path

import build_vpn_virus_protection_video as base


base.WORK = base.ROOT / "video" / "vpn-versus-tor"
base.SCENES = base.WORK / "scenes"
base.OUTPUT = base.WORK / "output"
base.SEGMENTS = base.WORK / "segments"
base.AUDIO = Path("/Users/juanpablogarcia/Downloads/vpn-versus-tor--which-provides.wav")
base.FINAL_NAME = "VPN-Versus-Tor-Which-Provides-More-Privacy.mp4"
base.DURATION = 61.550
base.SCENE_SEQUENCE = [
    (1, 0.000, 9.378),
    (2, 9.378, 15.280),
    (3, 15.280, 25.673),
    (4, 25.673, 30.487),
    (5, 30.487, 38.146),
    (6, 38.146, 45.434),
    (7, 45.434, 55.369),
    (8, 55.369, base.DURATION),
]
base.SENTENCES = [
    (0.260, 3.566, "VPN versus Tor: which provides more privacy?"),
    (4.471, 8.487, "Usually, Tor provides stronger anonymity—but it comes with trade-offs."),
    (9.378, 14.853, "A VPN sends your traffic through one encrypted tunnel to a server operated by the VPN company."),
    (15.280, 18.793, "Your internet provider can’t directly see which services you access,"),
    (19.092, 22.453, "but the VPN provider can technically see some connection information."),
    (22.833, 24.649, "You’re placing your trust in one company."),
    (25.673, 30.112, "Tor sends your traffic through multiple volunteer-operated servers, called relays."),
    (30.487, 37.103, "Each relay knows only part of the connection, making it much harder for one organization to connect your identity with your activity."),
    (38.146, 44.457, "However, Tor is normally much slower, some websites block it, and incorrect use can still reveal your identity."),
    (45.434, 49.848, "A VPN is generally better for speed, convenience and everyday privacy."),
    (50.712, 54.453, "Tor is generally better when stronger anonymity is the priority."),
    (55.369, 56.767, "Neither makes you invisible."),
    (57.054, 60.839, "The best choice depends on what you’re protecting—and who you’re protecting it from."),
]


if __name__ == "__main__":
    base.main()
