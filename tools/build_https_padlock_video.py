from pathlib import Path

import build_vpn_virus_protection_video as base


base.WORK = base.ROOT / "video" / "https-padlock"
base.SCENES = base.WORK / "scenes"
base.OUTPUT = base.WORK / "output"
base.SEGMENTS = base.WORK / "segments"
base.AUDIO = Path("/Users/juanpablogarcia/Downloads/what-does-the-https-padlock-in.wav")
base.FINAL_NAME = "What-Does-The-HTTPS-Padlock-Guarantee.mp4"
base.DURATION = 52.925
base.SCENE_SEQUENCE = [
    (1, 0.000, 5.198),
    (2, 5.198, 12.179),
    (3, 12.179, 17.520),
    (4, 17.520, 23.157),
    (5, 23.157, 29.784),
    (6, 29.784, 37.634),
    (7, 37.634, 47.936),
    (8, 47.936, base.DURATION),
]
base.SENTENCES = [
    (0.264, 4.258, "What does the HTTPS padlock in your browser actually guarantee?"),
    (5.198, 16.483, "It means your connection to that website is encrypted. Someone monitoring the network normally cannot read the specific pages you visit, passwords you enter, messages you send or payment information you provide."),
    (17.520, 22.204, "It also means the website presented a valid security certificate for that domain."),
    (23.157, 25.409, "But the padlock does not mean the website is honest."),
    (25.720, 29.401, "A phishing site can also use HTTPS and display a padlock."),
    (29.784, 36.528, "It doesn’t guarantee that downloads are safe, the company is trustworthy or your information won’t be misused after the website receives it."),
    (37.634, 46.985, "Think of HTTPS as a secure delivery service: it protects your information while it travels, but it cannot guarantee that the person receiving it has good intentions."),
    (47.936, 52.203, "The padlock means the connection is secure—not necessarily the website."),
]


if __name__ == "__main__":
    base.main()
