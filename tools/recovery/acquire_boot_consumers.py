"""Read fixed public boot components and bounded metadata; never reads /fdata."""
from pathlib import Path
import base64
import datetime as dt
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'evidence/boot-consumers-20261116-1620/live'
ADB = 'C:/tools/platform-tools/adb.exe'
SERIAL = '127.0.0.1:5555'
FP = 'Sony/BRAVIA_VH1_PA/BRAVIA_VH1:12/STT2.230505.001.S137/626241:user/release-keys'
SHA = '97738790f18837a41cc1fad9da11657210b5462d29882e272928a8187f9b7125'
FILES = ['/vendor/bin/setupdata','/vendor/bin/routing_private_network_service',
         '/system/etc/init/hw/init.rc','/system/etc/init/hw/init.usb.rc',
         '/system/etc/init/hw/init.usb.configfs.rc','/vendor/etc/init/hw/init.mt5895.rc',
         '/vendor/etc/init/hw/init.mt5895.usb.rc','/odm/etc/init/sony.common.rc',
         '/vendor/etc/fstab.late.mt5895']


def adb(*args):
    proc = subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,
                          encoding='utf-8',errors='replace',timeout=25)
    return {'utc':dt.datetime.now(dt.timezone.utc).isoformat(),
            'returncode':proc.returncode,'stdout':proc.stdout,'stderr':proc.stderr}


def shell(script):
    encoded = base64.b64encode(script.encode()).decode()
    result = adb('shell','printf %s '+encoded+' | base64 -d | sh')
    result['script'] = script
    return result


def save(name,value):
    with (OUT/name).open('x',encoding='utf-8') as stream:
        json.dump(value,stream,indent=2)
        stream.write('\n')


def main():
    OUT.mkdir(exist_ok=False)
    identity = shell('getprop ro.build.fingerprint; sha256sum /vendor/lib/modules/dtv_driver.ko; cat /proc/sys/kernel/random/boot_id')
    save('identity.json',identity)
    if identity['returncode'] or FP not in identity['stdout'].splitlines() or SHA+' ' not in identity['stdout']:
        raise SystemExit('Identity mismatch')
    binaries = OUT/'files'
    binaries.mkdir()
    records=[]
    for path in FILES:
        name = path.strip('/').replace('/','_')
        remote = shell('sha256sum '+path)
        save(name+'-hash.json',remote)
        pulled = adb('pull',path,str(binaries/name))
        save(name+'-pull.json',pulled)
        if not pulled['returncode']:
            local = binaries/name
            sha = hashlib.sha256(local.read_bytes()).hexdigest()
            if sha+' ' not in remote['stdout']:
                raise SystemExit('Host/device hash mismatch: '+path)
            records.append({'device_path':path,'local':name,'sha256':sha,'bytes':local.stat().st_size})
    save('artifacts.json',records)
    save('metadata.json',shell('''echo PUBLIC_RECIPE_METADATA
ls -ld /vendor/recipe/data
ls -l /vendor/recipe/data
echo STATE
getprop ro.boot.verifiedbootstate
getprop ro.boot.vbmeta.device_state
getprop sys.usb.adb.disabled
getprop vendor.sys.usb.adb.disabled
getprop init.svc.vendor_rpn_service
echo NETWORK
ip -o addr show dev eth0
echo FOREGROUND
dumpsys activity activities | grep mResumedActivity
'''))
    print(json.dumps(records,indent=2))


if __name__ == '__main__':
    main()
