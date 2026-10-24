import sys, zipfile
src = zipfile.ZipFile(sys.argv[1])
out = zipfile.ZipFile(sys.argv[2], 'w')
order = sorted(src.infolist(), key=lambda i: (i.filename != 'AndroidManifest.xml', i.filename))
for info in order:
    data = src.read(info.filename)
    zi = zipfile.ZipInfo(info.filename, date_time=(1981, 1, 1, 1, 1, 2))
    zi.compress_type = info.compress_type
    zi.external_attr = info.external_attr
    if zi.compress_type == zipfile.ZIP_STORED:
        off = out.fp.tell()
        base = off + 30 + len(zi.filename.encode('utf-8'))
        zi.extra = b'\x00' * ((-base) % 4)
        out.writestr(zi, data)
    else:
        out.writestr(zi, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
out.close()
# check
chk = zipfile.ZipFile(sys.argv[2])
for i in chk.infolist():
    if i.compress_type == zipfile.ZIP_STORED:
        dat = i.header_offset + 30 + len(i.filename.encode('utf-8')) + len(i.extra)
        assert dat % 4 == 0, i.filename
print('aligned', len(chk.infolist()), 'entries')
