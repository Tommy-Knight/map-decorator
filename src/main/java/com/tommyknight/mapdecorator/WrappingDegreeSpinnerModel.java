package com.tommyknight.mapdecorator;

import javax.swing.SpinnerNumberModel;

/**
 * Degrees spinner that wraps at both ends instead of stopping, so holding an arrow key turns the
 * object continuously through full revolutions rather than sticking at 0 or 360.
 *
 * 360 is the same heading as 0, so stepping up from 359 lands on 0 rather than showing the same
 * angle twice. 360 stays a legal value â€” {@code orientationToDeg} rounds the topmost orientations
 * up to it â€” it just isn't a stop the arrows produce on their way round.
 */
class WrappingDegreeSpinnerModel extends SpinnerNumberModel
{
	private static final int FULL_TURN = 360;

	WrappingDegreeSpinnerModel(int initialValue)
	{
		super(initialValue, 0, FULL_TURN, 1);
	}

	@Override
	public Object getNextValue()
	{
		int deg = getNumber().intValue();
		return deg >= FULL_TURN - 1 ? 0 : deg + 1;
	}

	@Override
	public Object getPreviousValue()
	{
		int deg = getNumber().intValue();
		return deg <= 0 ? FULL_TURN - 1 : deg - 1;
	}
}
