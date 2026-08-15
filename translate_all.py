import os
import xml.etree.ElementTree as ET
import time
from deep_translator import GoogleTranslator

# Mapping of Android locale to Google Translate locale
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

# The files we already translated
SKIP_LOCALES = ['es', 'pt', 'ru', 'in', 'zh-rCN', 'fr', 'de', 'it', 'vi', 'tr', 'pl', 'ar']

base_file = 'app/src/main/res/values/strings.xml'
tree = ET.parse(base_file)
root = tree.getroot()

original_strings = {}
for elem in root.findall('string'):
    name = elem.get('name')
    text = elem.text
    if text:
        original_strings[name] = text

def escape_placeholders(text):
    text = text.replace('%1$s', '___STR___')
    text = text.replace('%1$d%%', '___INT___')
    text = text.replace("\\'", '___APOS___')
    return text

def unescape_placeholders(text):
    text = text.replace('___STR___', '%1$s')
    text = text.replace('___INT___', '%1$d%%')
    text = text.replace('___APOS___', "\\'")
    text = text.replace('___ STR ___', '%1$s')
    text = text.replace('___ INT ___', '%1$d%%')
    text = text.replace('___ APOS ___', "\\'")
    text = text.replace('___str___', '%1$s')
    text = text.replace('___int___', '%1$d%%')
    text = text.replace('___apos___', "\\'")
    text = text.replace("'", "\\'") # Ensure all quotes are escaped in Android XML
    return text

for android_locale, gt_locale in LOCALES.items():
    if android_locale in SKIP_LOCALES:
        continue
        
    print(f"Translating to {android_locale}...")
    target_dir = f'app/src/main/res/values-{android_locale}'
    os.makedirs(target_dir, exist_ok=True)
    
    translator = GoogleTranslator(source='en', target=gt_locale)
    
    new_root = ET.Element('resources')
    
    for name, text in original_strings.items():
        if name in ['app_name', 'settings_entry_title']:
            # don't translate app name
            new_elem = ET.SubElement(new_root, 'string', {'name': name})
            new_elem.text = text
            continue
            
        escaped = escape_placeholders(text)
        try:
            translated = translator.translate(escaped)
            final_text = unescape_placeholders(translated)
        except Exception as e:
            print(f"Error translating {name} to {android_locale}: {e}")
            final_text = text # fallback
            
        new_elem = ET.SubElement(new_root, 'string', {'name': name})
        new_elem.text = final_text
        
    # Write file
    new_tree = ET.ElementTree(new_root)
    ET.indent(new_tree, space="    ", level=0)
    new_tree.write(f'{target_dir}/strings.xml', encoding='utf-8', xml_declaration=True)

print("Done translating!")
