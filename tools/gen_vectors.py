#!/usr/bin/env python3
"""Generate test vectors for the numpwd derivation from the real openssl binary.

For every (key name, password) pair the script records two things:

- the crypt string from `openssl passwd -6 -salt NAME PASSWORD` (argv mode),
- the digits printed by the exact pipeline the app reproduces, with the password
  typed at the interactive prompt on a pseudo-terminal:
  `openssl passwd -6 -salt NAME | tr -dc '[0-9]'`.

Interactive mode matters because it rejects passwords longer than the prompt
buffer, which argv mode silently truncates.

Output is a TSV file: name_hex, password_hex, crypt_hex (or "-"), digits (or "!ERROR").
"""

import argparse
import fcntl
import os
import random
import subprocess
import sys
import termios

ASCII_PRINTABLE = "".join(chr(c) for c in range(0x20, 0x7F))
UNICODE_LETTERS = "ąćęłńóśźżĄĆĘŁŃÓŚŹŻäöüßéèñçøåжщюяΩπλ中文字日本語😀🔑"
INTERACTIVE_TIMEOUT_S = 30


def argv_crypt_hex(name: bytes, password: bytes) -> str:
    out = subprocess.run(
        ["openssl", "passwd", "-6", "-salt", name, password],
        capture_output=True,
        check=True,
    ).stdout.rstrip(b"\n")
    return "-" if out == b"<NULL>" else out.hex()


def _make_controlling_tty(slave_fd: int) -> None:
    os.setsid()
    fcntl.ioctl(slave_fd, termios.TIOCSCTTY, 0)


def interactive_digits(name: bytes, password: bytes) -> str:
    master, slave = os.openpty()
    try:
        openssl = subprocess.Popen(
            ["openssl", "passwd", "-6", "-salt", name],
            stdin=slave,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            preexec_fn=lambda: _make_controlling_tty(slave),
        )
        os.write(master, password + b"\n")
        crypt_out, _ = openssl.communicate(timeout=INTERACTIVE_TIMEOUT_S)
    finally:
        os.close(slave)
        os.close(master)
    if openssl.returncode != 0:
        return "!ERROR"
    digits = subprocess.run(
        ["tr", "-dc", "[0-9]"], input=crypt_out, capture_output=True, check=True
    ).stdout
    return digits.decode("ascii") if digits else "!ERROR"


def random_text(rng: random.Random, alphabet: str, max_len: int) -> str:
    return "".join(rng.choice(alphabet) for _ in range(rng.randint(1, max_len)))


def fixed_cases() -> list[tuple[bytes, bytes]]:
    cases = [
        ("test", "abc"),
        ("", "abc"),
        ("test", ""),
        ("a$b", "abc"),
        ("$", "x"),
        ("a[b]c", "x"),
        ("[0-9]", "x"),
        ("with space", "pass word"),
        ("rounds=1000$xy", "abc"),
        ("rounds=$xy", "abc"),
        ("rounds= 1200$xy", "abc"),
        ("rounds=+1500$q", "abc"),
        ("rounds=-0$q", "abc"),
        ("rounds=00001001$q", "abc"),
        ("rounds=12a$xy", "abc"),
        ("rounds=10", "abc"),
        ("rounds=1000$", "abc"),
        ("rounds=1000$rounds=2000$x", "abc"),
        ("rounds=999$x", "abc"),
        ("Rounds=1000$q", "abc"),
        ("zażółćgęśląjaźńxyz", "abc"),
        ("żżżżżżżaż", "abc"),
        ("0123456789abcdefXYZ", "abc"),
        ("klucz", "zażółć gęślą jaźń"),
        ("emoji", "🔑🔑🔑"),
    ]
    out = [(n.encode(), p.encode()) for n, p in cases]
    for length in (63, 64, 65, 127, 128, 129, 255, 256, 257, 258, 259, 300):
        out.append((b"len%d" % length, b"p" * length))
    return out


def random_cases(rng: random.Random, count: int) -> list[tuple[bytes, bytes]]:
    out = []
    for _ in range(count):
        name_alphabet = rng.choice([ASCII_PRINTABLE, UNICODE_LETTERS, ASCII_PRINTABLE + UNICODE_LETTERS])
        name = random_text(rng, name_alphabet, 24)
        if rng.random() < 0.1:
            name = "rounds=%d$%s" % (rng.randint(900, 6000), name)
        pw_alphabet = rng.choice([ASCII_PRINTABLE, ASCII_PRINTABLE + UNICODE_LETTERS])
        password = random_text(rng, pw_alphabet, rng.choice([16, 80, 200]))
        out.append((name.encode(), password.encode()))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--count", type=int, default=220, help="number of random cases")
    parser.add_argument("--seed", type=int, default=20261117)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    rows = []
    for name, password in fixed_cases() + random_cases(rng, args.count):
        rows.append(
            "\t".join(
                [name.hex(), password.hex(), argv_crypt_hex(name, password), interactive_digits(name, password)]
            )
        )
    with open(args.output, "w", encoding="ascii") as f:
        f.write("# name_hex\tpassword_hex\tcrypt_argv_hex\tdigits_interactive\n")
        f.write("\n".join(rows) + "\n")
    print("wrote %d vectors to %s" % (len(rows), args.output), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
