UPDATE products SET category = COALESCE(category, '外设'),
                    tags     = COALESCE(tags, '键盘,机械键盘,keyboard,输入设备')
WHERE name = '机械键盘 K87';

UPDATE products SET category = COALESCE(category, '外设'),
                    tags     = COALESCE(tags, '鼠标,鼠标器,无线,mouse,输入设备')
WHERE name = '无线鼠标 M330';

UPDATE products SET category = COALESCE(category, '显示'),
                    tags     = COALESCE(tags, '显示器,屏幕,2K,monitor')
WHERE name = '27 英寸显示器';

UPDATE products SET category = COALESCE(category, '外设'),
                    tags     = COALESCE(tags, '扩展坞,拓展坞,USB-C,转接,dock')
WHERE name = 'USB-C 扩展坞';

UPDATE products SET category = COALESCE(category, '音频'),
                    tags     = COALESCE(tags, '耳麦,耳机,headset,降噪')
WHERE name = '降噪耳麦';

UPDATE products SET category = COALESCE(category, '家具'),
                    tags     = COALESCE(tags, '椅子,工学椅,座椅,chair')
WHERE name = '人体工学椅';

UPDATE products SET category = COALESCE(category, '照明'),
                    tags     = COALESCE(tags, '灯,氛围灯,灯带,rgb')
WHERE name = '桌面氛围灯';

UPDATE products SET category = COALESCE(category, '外设'),
                    tags     = COALESCE(tags, '鼠标垫,桌垫,配件')
WHERE name = '限量鼠标垫';
