import os
import xml.etree.ElementTree as ET
from deep_translator import GoogleTranslator

LOCALES = {
    'af': 'af', 'am': 'am', 'bg': 'bg', 'bn': 'bn', 'ca': 'ca', 'cs': 'cs',
    'da': 'da', 'el': 'el', 'et': 'et', 'fa': 'fa', 'fi': 'fi', 'hi': 'hi',
    'hr': 'hr', 'hu': 'hu', 'hy': 'hy', 'is': 'is', 'iw': 'iw', 'ja': 'ja',
    'ka': 'ka', 'kk': 'kk', 'km': 'km', 'kn': 'kn', 'ko': 'ko', 'lo': 'lo',
    'lt': 'lt', 'lv': 'lv', 'mk': 'mk', 'ml': 'ml', 'mr': 'mr', 'ms': 'ms',
    'my': 'my', 'nb': 'no', 'ne': 'ne', 'nl': 'nl', 'ro': 'ro', 'si': 'si',
    'sk': 'sk', 'sl': 'sl', 'sr': 'sr', 'sv': 'sv', 'sw': 'sw', 'ta': 'ta',
    'te': 'te', 'th': 'th', 'tl': 'tl', 'uk': 'uk', 'ur': 'ur', 'uz': 'uz',
    'zu': 'zu', 'pt-rBR': 'pt', 'zh-rTW': 'zh-TW', 'zh-rHK': 'zh-TW'
}

base_file = 'app/src/main/res/values/strings.xml'
tree = ET.parse(base_file)
root = tree.getroot()

keys = []
texts = []
for elem in root.findall('string'):
    name = elem.get('name')
    text = elem.text
    if name not in ['app_name', 'settings_entry_title']:
        keys.append(name)
        text = text.replace('%1$s', '___STR___').replace('%1$d%%', '___INT___').replace("\\'", '___APOS___')
        texts.append(text)

batch_text = "\n".join(texts)

for android_locale, gt_locale in LOCALES.items():
    print(f"Translating to {android_locale}...")
    target_dir = f'app/src/main/res/values-{android_locale}'
    if os.path.exists(f'{target_dir}/strings.xml'):
        continue
    os.makedirs(target_dir, exist_ok=True)
    
    try:
        translated_batch = GoogleTranslator(source='en', target=gt_locale).translate(batch_text)
        translated_lines = translated_batch.split('\n')
        
        # fallback if line counts don't match (API glitch)
        if len(translated_lines) != len(keys):
            print(f"Mismatch for {android_locale}, skipping...")
            continue
            
        new_root = ET.Element('resources')
        
        # Add non-translated keys first
        for elem in root.findall('string'):
            name = elem.get('name')
            if name in ['app_name', 'settings_entry_title']:
                ET.SubElement(new_root, 'string', {'name': name}).text = elem.text
                
        # Add translated keys
        for key, t_text in zip(keys, translated_lines):
            t_text = t_text.replace('___STR___', '%1$s').replace('___INT___', '%1$d%%').replace('___APOS___', "\\'")
            t_text = t_text.replace('___ STR ___', '%1$s').replace('___ INT ___', '%1$d%%').replace('___ APOS ___', "\\'")
            t_text = t_text.replace("'", "\\'")
            ET.SubElement(new_root, 'string', {'name': key}).text = t_text
            
        new_tree = ET.ElementTree(new_root)
        ET.indent(new_tree, space="    ", level=0)
        new_tree.write(f'{target_dir}/strings.xml', encoding='utf-8', xml_declaration=True)
    except Exception as e:
        print(f"Failed {android_locale}: {e}")

print("Done translating!")
