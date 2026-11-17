# numpwd

Android app that derives numeric passwords from a key name and a password,
compatible with `openssl passwd -6`.

## What it does

The app keeps a list of key names. Tap a key, type a password, and numpwd
shows a six-character code in large print: the first six characters that this
pipeline prints for the same password typed at the openssl prompt.

```sh
openssl passwd -6 -salt KEYNAME | tr -dc '[0-9]' | head -c6
```

Below the code the dialog shows the whole `tr` output (the same pipeline
without `head -c6`), grouped in fours. **Copy** copies the code, **Copy all**
copies the whole string without spaces.

For example, key `test` with password `abc` gives the code `659550`; the whole
string is `65955012972`.

Because the result is a plain OpenSSL computation, you can recompute any
password on a computer without the app.

## Features

- Only key names are stored. The password is never saved or logged and is
  excluded from autofill and keyboard learning.
- The password field shows a dot for every character from the first moment,
  also when the system "Show passwords" setting would briefly show the last
  typed character. There is no show/hide toggle.
- The password and result dialogs set `FLAG_SECURE`, so they do not appear in
  screenshots or screen recordings. The key list can still be captured.
- Copy buttons for the code and the whole string. The clipboard is cleared
  after 30 seconds if it still holds the copied value, and the result closes
  after a minute.
- No permissions and no network access. Android backup includes only the key
  name list.

## Compatibility notes

- OpenSSL takes the salt as raw UTF-8 bytes and cuts it to 16 bytes, keeps `$`
  in it and accepts a `rounds=N$` prefix. numpwd ports this from
  `apps/passwd.c` instead of using a generic SHA-512 crypt library, which
  would reject such salts.
- The interactive prompt keeps at most 256 bytes of the password and fails on
  input longer than 258 bytes. numpwd does the same.
- `tr -dc '[0-9]'` also keeps `[` and `]`, because the brackets are part of
  the character set. A key name containing brackets therefore shows them in
  the result too, and they count towards the six characters of the code like
  `head -c6` counts them (key `[]`, password `abc` gives the code `6[]009`).
- The code always starts with `6`: it comes from the `$6$` prefix of the crypt
  string, before the salt and the hash.
- The hash part has 86 characters, each a digit with probability 10/64. For a
  key name without digits, about 0.15% of passwords leave fewer than five
  digits in the hash, so the whole output is shorter than six characters. The
  app then shows what there is, like `head -c6`: key `test` with password
  `pw2002` gives `62960`.

`tools/gen_vectors.py` generates test vectors from the openssl binary;
`mobile/src/test/resources/openssl_vectors.tsv` holds the committed set.

## Install

Add the F-Droid repository
<https://d33mobile.github.io/numpwd-fdroid/repo> (fingerprint
`3f59cdb18ac033fb4d7fa118231442a4504c3721b68bab196c60b717f3e392f3`) or open
<https://d33mobile.github.io/numpwd-fdroid/> on the phone.

## Build

Requires JDK 17 or newer and the Android SDK (platform 36).

```sh
./gradlew :mobile:assembleDebug
```

Release builds are signed when `NUMPWD_KEYSTORE`, `NUMPWD_KEYSTORE_PASS`,
`NUMPWD_KEY_ALIAS` and `NUMPWD_KEY_PASS` are set.

## Tests

```sh
./gradlew :mobile:testDebugUnitTest          # crypt vectors, key name rules
./gradlew :mobile:connectedDebugAndroidTest  # UI test on a device or emulator
scripts/ci-vector-check.sh 1234              # regenerate vectors with the local openssl
```

CI (`.github/workflows/build.yml`) runs all three plus Android lint.

## License

numpwd is a fork of [FreeOTP for Android](https://github.com/freeotp/freeotp-android)
by Red Hat and contributors, and like it is licensed under the Apache License 2.0
(see [COPYING](COPYING)). The OTP features were removed; the git history keeps
the upstream authorship.
