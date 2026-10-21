"""Geometry of the real minimap (measured on the Thor frames, 1920x1080) and the common crop helper."""
from __future__ import annotations

import numpy as np

# ring centre-line circle fitted on the static HUD median of 448 frames.  Pixel-INDEX convention
# (pixel centres at integers, as used by cv2.remap / array indices).
CX_IDX, CY_IDX, R_RING = 210.0, 155.6, 133.4
# the prototype's Calib uses the continuous convention (pixel i covers [i, i+1))
CX, CY = CX_IDX + 0.5, CY_IDX + 0.5
