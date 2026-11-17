import unittest
import numpy as np
from frame_diagnostics import measure


class Diagnostics(unittest.TestCase):
    def test_static_horizontal_edge_is_baseline(self):
        a=np.zeros((4,16,20));a[:,8:]=90
        r,t,e=measure(a)
        self.assertEqual(r['pair_mae_max'],0)
        self.assertEqual(float(e.max()),0)

    def test_transient_split_is_localized_without_diagnosis(self):
        a=np.zeros((5,16,20));a[2,8:]=80
        r,t,e=measure(a)
        self.assertEqual(r['max_row_edge_excess']['frame_1based'],3)
        self.assertEqual(r['max_row_edge_excess']['boundary_after_row_0based'],7)
        self.assertEqual(float(e[7,2]),80)
        self.assertTrue(np.all(t[:8]==0))
        self.assertIn('no tear',r['interpretation'])

    def test_alternating_rows_raise_proxy(self):
        a=np.zeros((3,16,20));a[:,1::2]=100
        r,_,_=measure(a)
        self.assertEqual(r['vertical_extremum_proxy_median'],100)

    def test_invalid_and_unsigned_subtraction(self):
        a=np.stack([np.full((4,4),255,dtype='uint8'),np.zeros((4,4),dtype='uint8')])
        self.assertEqual(measure(a)[0]['pair_mae_max'],255)
        for bad in [np.zeros((1,4,4)),np.full((2,4,4),np.nan),np.full((2,4,4),256)]:
            with self.assertRaises(ValueError):measure(bad)


if __name__=='__main__':unittest.main()
