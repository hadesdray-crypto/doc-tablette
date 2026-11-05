#!/usr/bin/env python3
"""Fabrique des codes d'activation Doc Tablette (même calcul que l'appli).

Utilisation :  python3 make_licence.py <secret> [nombre]
Chaque code est à envoyer à un acheteur après son paiement PayPal.
"""
import hashlib, hmac, secrets, sys

ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def mac(secret: str, id8: str) -> str:
    h = hmac.new(secret.encode(), ("DT1:" + id8).encode(), hashlib.sha256).digest()
    v = int.from_bytes(h[:5], "big")
    return "".join(ALPHA[(v >> (5 * i)) & 31] for i in range(7, -1, -1))


def make(secret: str, id8: str = None) -> str:
    id8 = id8 or "".join(secrets.choice(ALPHA) for _ in range(8))
    c = mac(secret, id8)
    return f"DT-{id8[:4]}-{id8[4:]}-{c[:4]}-{c[4:]}"


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for _ in range(int(sys.argv[2]) if len(sys.argv) > 2 else 1):
        print(make(sys.argv[1]))
