import os
import numpy as np
from PIL import Image, ImageDraw

base_dir = os.path.dirname(os.path.abspath(__file__))
source_path = os.path.join(base_dir, 'source.jpg')
res_path = os.path.join(base_dir, 'app/src/main/res')

# Densities and sizes for legacy/round
densities = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

# Adaptive foreground is 108dp base
foreground_densities = {
    'mdpi': 108,
    'hdpi': 162,
    'xhdpi': 216,
    'xxhdpi': 324,
    'xxxhdpi': 432
}

img = Image.open(source_path).convert("RGBA")
arr_src = np.array(img)
corners = [arr_src[0, 0][:3], arr_src[0, -1][:3], arr_src[-1, 0][:3], arr_src[-1, -1][:3]]
bg_rgb = tuple(np.mean(corners, axis=0).astype(int))
bg_hex = f"#{bg_rgb[0]:02X}{bg_rgb[1]:02X}{bg_rgb[2]:02X}"
print(f"Extracted background color: {bg_hex}")

# 1. Update background color resource
values_folder = os.path.join(res_path, 'values')
os.makedirs(values_folder, exist_ok=True)
color_file = os.path.join(values_folder, 'ic_launcher_background.xml')
with open(color_file, 'w') as f:
    f.write(f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{bg_hex}</color>\n</resources>\n')

# 2. Adaptive foregrounds (108dp base, scaled 90/108 into safe zone)
for density, size in foreground_densities.items():
    folder = os.path.join(res_path, f'mipmap-{density}')
    os.makedirs(folder, exist_ok=True)
    
    scaled_sz = size * 90 // 108
    pad = (size - scaled_sz) // 2
    resized = img.resize((scaled_sz, scaled_sz), Image.Resampling.LANCZOS)
    arr = np.array(resized)
    
    pad_t = pad
    pad_b = size - scaled_sz - pad_t
    pad_l = pad
    pad_r = size - scaled_sz - pad_l
    
    padded_arr = np.pad(arr, ((pad_t, pad_b), (pad_l, pad_r), (0, 0)), mode='edge')
    fg = Image.fromarray(padded_arr)
    fg.save(os.path.join(folder, 'ic_launcher_foreground.png'))

# 3. Legacy square & round icons
for density, size in densities.items():
    folder = os.path.join(res_path, f'mipmap-{density}')
    os.makedirs(folder, exist_ok=True)
    
    # Legacy square: smooth rounded-corner icon with subtle radius
    scaled_sz = int(size * 0.96)
    pad = (size - scaled_sz) // 2
    resized = img.resize((scaled_sz, scaled_sz), Image.Resampling.LANCZOS)
    
    square_img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    # Round rectangle mask (22% corner radius)
    corner_radius = int(size * 0.22)
    mask_sq = Image.new('L', (size, size), 0)
    draw_sq = ImageDraw.Draw(mask_sq)
    draw_sq.rounded_rectangle((pad, pad, pad + scaled_sz, pad + scaled_sz), radius=corner_radius, fill=255)
    
    base_sq = Image.new('RGBA', (size, size), (*bg_rgb, 255))
    base_sq.paste(resized, (pad, pad))
    square_img.paste(base_sq, (0, 0), mask=mask_sq)
    square_img.save(os.path.join(folder, 'ic_launcher.png'))
    
    # Legacy round: circular mask from scaled adaptive preview (crop to 64dp to clear squircle line)
    round_img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    mask_rd = Image.new('L', (size, size), 0)
    draw_rd = ImageDraw.Draw(mask_rd)
    draw_rd.ellipse((0, 0, size, size), fill=255)
    
    # Get high-res adaptive foreground for this density ratio
    fg_size = size * 108 // 48
    fg_path = os.path.join(folder, 'ic_launcher_foreground.png')
    fg_full = Image.open(fg_path)
    sz_crop = fg_size * 64 // 108
    crop_pad = (fg_size - sz_crop) // 2
    cropped = fg_full.crop((crop_pad, crop_pad, crop_pad + sz_crop, crop_pad + sz_crop))
    cropped = cropped.resize((size, size), Image.Resampling.LANCZOS)
    
    round_img.paste(cropped, (0, 0), mask=mask_rd)
    round_img.save(os.path.join(folder, 'ic_launcher_round.png'))

# 4. Generate adaptive XMLs
anydpi_folder = os.path.join(res_path, 'mipmap-anydpi-v26')
os.makedirs(anydpi_folder, exist_ok=True)

adaptive_xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
'''

with open(os.path.join(anydpi_folder, 'ic_launcher.xml'), 'w') as f:
    f.write(adaptive_xml)

with open(os.path.join(anydpi_folder, 'ic_launcher_round.xml'), 'w') as f:
    f.write(adaptive_xml)

print("Successfully generated all launcher icon assets!")
