import xml.etree.ElementTree as ET
import subprocess

subprocess.run(['adb', '-H', 'sz1.natnps.cn', '-P', '15376', 'pull', '/sdcard/ui.xml', 'ui.xml'])
tree = ET.parse('ui.xml')
for node in tree.iter():
    text = node.attrib.get('text', '')
    if text:
        print(f"{node.attrib.get('class')}: {text} bounds={node.attrib.get('bounds')}")
