#!/usr/bin/env python3
"""Small replaceable profile UI placeholders. Not run by the resource-pack builder."""
import json
from pathlib import Path
from draw_medals import png

ROOT = Path(__file__).resolve().parents[1] / 'resourcepack/assets/f8resurs'
PATTERN = ['00011000', '00011000', '00111000', '01111110',
           '11111111', '11111111', '11111110', '01111100']

def main():
    (ROOT / 'textures/gui').mkdir(parents=True, exist_ok=True)
    providers = []
    for name, color, shape, glyph in (
        ('profile_like', (112, 207, 134, 255), PATTERN, '\ue101'),
        ('profile_dislike', (221, 116, 116, 255), list(reversed(PATTERN)), '\ue102'),
    ):
        pixels = [[color if pixel == '1' else (0, 0, 0, 0) for pixel in row] for row in shape]
        (ROOT / 'textures/gui' / f'{name}.png').write_bytes(png(pixels, 8, 8))
        providers.append({'type': 'bitmap', 'file': f'f8resurs:gui/{name}.png', 'height': 8, 'ascent': 7, 'chars': [glyph]})
    for metal, glyph in (('copper', '\ue103'), ('silver', '\ue104'), ('gold', '\ue105')):
        providers.append({'type': 'bitmap', 'file': f'f8resurs:item/medal_{metal}.png', 'height': 8, 'ascent': 7, 'chars': [glyph]})
    # Картинки префиксов: файлам pref1.png…pref10.png отвечают глифы \ue106…\ue10F.
    for number in range(1, 11):
        glyph = chr(0xE105 + number)
        providers.append({'type': 'bitmap', 'file': f'f8resurs:item/pref{number}.png', 'height': 8, 'ascent': 7, 'chars': [glyph]})
    (ROOT / 'font').mkdir(parents=True, exist_ok=True)
    (ROOT / 'font/profile_ui.json').write_text(json.dumps({'providers': providers}, indent=2) + '\n')
    print('Profile icons and font: OK')

if __name__ == '__main__':
    main()
