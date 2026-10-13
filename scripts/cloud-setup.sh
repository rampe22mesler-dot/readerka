#!/usr/bin/env bash
set -euo pipefail
cd /workspace/readerka
tools_dir=/workspace/toolchains
mkdir -p "$tools_dir/downloads" "$tools_dir/android-sdk/cmdline-tools" "$tools_dir/android-user" "$tools_dir/gradle-home"
if [[ ! -x "$tools_dir/jdk-17.0.16+8/bin/javac" ]]; then
    jdk_release='https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz'
    curl --fail --location --retry 2 "$jdk_release" -o "$tools_dir/downloads/jdk17.tar.gz"
    curl --fail --location --retry 2 "$jdk_release.sha256.txt" -o "$tools_dir/downloads/jdk17.sha256"
    python - <<'PY'
import hashlib, pathlib
p = pathlib.Path('/workspace/toolchains/downloads')
assert hashlib.sha256((p/'jdk17.tar.gz').read_bytes()).hexdigest() == (p/'jdk17.sha256').read_text().split()[0], 'JDK checksum mismatch'
PY
    tar -xzf "$tools_dir/downloads/jdk17.tar.gz" -C "$tools_dir"
fi
source scripts/cloud-env.sh
if [[ ! -x "$ANDROID_HOME/cmdline-tools/11.0/bin/sdkmanager" ]]; then
    curl --fail --location --retry 2 https://dl.google.com/android/repository/repository2-1.xml -o "$tools_dir/downloads/android-repository.xml"
    curl --fail --location --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o "$tools_dir/downloads/android-cli.zip"
    python - <<'PY'
import hashlib, pathlib, xml.etree.ElementTree as E
p = pathlib.Path('/workspace/toolchains/downloads')
for a in E.parse(p/'android-repository.xml').getroot().iter('archive'):
    u = a.find('complete/url')
    if u is not None and u.text == 'commandlinetools-linux-11076708_latest.zip':
        c = a.find('complete/checksum')
        assert hashlib.new(c.attrib.get('type','sha1'), (p/'android-cli.zip').read_bytes()).hexdigest() == c.text, 'SDK CLI checksum mismatch'
        break
else: raise SystemExit('Official SDK checksum unavailable')
PY
    unzip -q "$tools_dir/downloads/android-cli.zip" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/11.0"
fi
# Configure Java for the platform HTTPS proxy. Preserve other Gradle settings; never log credentials.
python - <<'PY'
import os, pathlib, subprocess, urllib.parse, shlex
root = pathlib.Path('/workspace/toolchains')
props = {}
proxy = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY', os.environ.get('https_proxy','')))
if proxy.hostname:
    for protocol in ['http','https']:
        props[f'{protocol}.proxyHost'] = proxy.hostname
        props[f'{protocol}.proxyPort'] = str(proxy.port or 80)
    props['http.nonProxyHosts'] = 'localhost|127.*|[::1]'
cert = os.environ.get('CODEX_PROXY_CERT')
if cert and pathlib.Path(cert).is_file():
    trust = root/'jdk-proxy-trust.jks'
    import shutil
    shutil.copyfile(pathlib.Path(os.environ['JAVA_HOME'])/'lib/security/cacerts', trust)
    subprocess.run([str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/keytool'), '-importcert','-noprompt','-alias','readerka-cloud-proxy','-file',cert,'-keystore',str(trust),'-storepass','changeit'],check=True,capture_output=True)
    props['javax.net.ssl.trustStore'] = str(trust)
    props['javax.net.ssl.trustStorePassword'] = 'changeit'
(root/'java-network-options').write_text(' '.join(shlex.quote(f'-D{k}={v}') for k,v in props.items()))
gp = root/'gradle-home/gradle.properties'
lines = gp.read_text().splitlines() if gp.exists() else []
keys = {'systemProp.' + k for k in props}
lines = [line for line in lines if line.split('=',1)[0] not in keys]
gp.write_text('\n'.join(lines + [f'systemProp.{k}={v}' for k,v in props.items()])+'\n')
PY
export JAVA_OPTS="$(cat "$tools_dir/java-network-options")"
(yes 2>/dev/null || true) | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > "$tools_dir/downloads/licenses.log"
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools' 'ndk;26.3.11579264' 'emulator' 'system-images;android-28;default;x86_64' 'system-images;android-30;default;x86_64'
if [[ ! -f local.properties ]]; then printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties; fi
./gradlew assembleDebug testDebugUnitTest --console=plain
