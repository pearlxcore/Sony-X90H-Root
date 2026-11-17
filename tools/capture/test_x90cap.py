import struct
import tempfile
import unittest
from pathlib import Path
import x90cap


class CaptureFormat(unittest.TestCase):
    def fixture(self):
        header=x90cap.FILE.pack(b'X90CAP2\0',2,14,8,16,2,3)
        payload=bytes(range(192))
        return header+x90cap.FRAME.pack(1,100,0,192,110)+payload+x90cap.FRAME.pack(2,200,1,192,210)+payload

    def parse(self,data):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'capture';path.write_bytes(data)
            return x90cap.read_capture(path)

    def test_valid(self):
        result=x90cap.describe(self.parse(self.fixture()))
        self.assertEqual(result['frames'],2)
        self.assertEqual(result['unique_active_pixel_hashes'],1)

    def test_reject_truncation_and_trailer(self):
        for data in [b'',self.fixture()[:-1],self.fixture()+b'x']:
            with self.assertRaises(ValueError):self.parse(data)

    def test_reject_bad_fields(self):
        for offset,format,value in [(8,'I',9),(20,'I',15),(24,'I',0),(32,'Q',2),
                                    (48,'I',3),(52,'I',200),(56,'Q',50)]:
            data=bytearray(self.fixture());struct.pack_into('<'+format,data,offset,value)
            with self.assertRaises(ValueError):self.parse(data)

    def test_ignore_padding_in_hashes(self):
        data=bytearray(self.fixture())
        second=32+32+192+32
        data[second+14]=255
        self.assertEqual(x90cap.describe(self.parse(data))['unique_active_pixel_hashes'],1)


if __name__=='__main__':unittest.main()
