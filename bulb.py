"""Set color, white balance, and brightness on a WiZ smart bulb over the local network."""

import colorsys
import json
import logging
import socket

log = logging.getLogger("bulb")

BULB_ADDR = ("192.168.2.202", 38899)


def ask(prompt):
    while True:
        try:
            value = int(input(prompt))
            if 0 <= value <= 100:
                return value
        except ValueError:
            pass
        log.warning("enter a whole number from 0 to 100")


def channels(color, whiteness):
    """Crossfade the RGB LEDs against the two white LEDs along the whiteness axis.

    Whiteness runs cold white (0) through pure color (50) to warm white (100).
    Distance from the midpoint sets how much white is mixed in; the side it
    falls on picks which white channel carries it.
    """
    tilt = (whiteness - 50) / 50.0
    white = round(abs(tilt) * 255)
    # The white LEDs draw roughly 4x the current of the color ones, so RGB has
    # to fade out as white comes up or the color washes out to nothing.
    scale = 255 * (1.0 - abs(tilt))
    r, g, b = (round(v * scale) for v in colorsys.hsv_to_rgb(color / 100.0, 1.0, 1.0))
    return {
        "r": r,
        "g": g,
        "b": b,
        "c": white if tilt < 0 else 0,
        "w": white if tilt > 0 else 0,
    }


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")

    color = ask("Color      (0-100): ")
    whiteness = ask("White      (0 cold / 50 color / 100 warm): ")
    brightness = ask("Brightness (0-100): ")

    # setPilot is a full-state write, so every channel goes in one packet --
    # anything left out is set to zero by the bulb.
    # The firmware ignores any dimming below 10, so 0 has to switch the bulb
    # off outright rather than dim it further.
    params = {"state": brightness > 0, "dimming": max(10, brightness)}
    params.update(channels(color, whiteness))

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(5)
    sock.sendto(json.dumps({"method": "setPilot", "params": params}).encode(), BULB_ADDR)
    try:
        reply, _ = sock.recvfrom(1024)
        log.info("sent %s, bulb replied: %s", json.dumps(params), reply.decode())
    except socket.timeout:
        log.error("no reply from %s:%d", *BULB_ADDR)


if __name__ == "__main__":
    main()
