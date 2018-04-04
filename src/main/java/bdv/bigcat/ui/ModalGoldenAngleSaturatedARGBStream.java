/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package bdv.bigcat.ui;

import bdv.bigcat.control.Wheel;
import bdv.bigcat.label.FragmentSegmentAssignment;
import bdv.bigcat.label.SegmentAssignment;


/**
 * Generates a stream of saturated colors.  Colors are picked from a radial
 * projection of the RGB colors {red, yellow, green, cyan, blue, magenta}.
 * Adjacent colors along the discrete id axis are separated by the golden
 * angle, making them reasonably distinct.  Changing the seed of the stream
 * makes a new sequence.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ModalGoldenAngleSaturatedARGBStream extends GoldenAngleSaturatedARGBStream implements Wheel
{
	final static public int NORMAL = 0;
	final static public int HIDE_COMPLETE = 1;
	final static public int SELECTED_ONLY = 2;

	protected int mode = 0;

	final protected SegmentAssignment completeSegments;

	public ModalGoldenAngleSaturatedARGBStream(
			final FragmentSegmentAssignment assignment,
			final SegmentAssignment completeSegments )
	{
		super( assignment );
		this.completeSegments = completeSegments;
		seed = 1;
	}

	@Override
	public synchronized void advance()
	{
		++mode;
		if ( mode == 3 )
			mode = 0;
		clearCache();
	}

	@Override
	public synchronized void regress()
	{
		--mode;
		if ( mode == -1 )
			mode = 2;
		clearCache();
	}

	@Override
	public void setMode( final int value )
	{
		mode = value % 3;
		clearCache();
	}

	@Override
	public int getMode()
	{
		return mode;
	}

	@Override
	public int argb( final long fragmentId )
	{
		int argb = fragmentARGBCache.get( fragmentId );

		if ( argb == 0x00000000 )
		{
			final long segmentId = assignment.getSegment( fragmentId );

			argb = segmentARGBCache.get( segmentId );

			if ( argb == 0x00000000 )
			{
				double x = getDouble( seed + segmentId );
				x *= 6.0;
				final int k = ( int )x;
				final int l = k + 1;
				final double u = x - k;
				final double v = 1.0 - u;

				final int r = interpolate( rs, k, l, u, v );
				final int g = interpolate( gs, k, l, u, v );
				final int b = interpolate( bs, k, l, u, v );

				argb = argb( r, g, b, alpha );

				if ( ( mode == SELECTED_ONLY && segmentId != activeSegment ) || ( mode == HIDE_COMPLETE && completeSegments.contains( segmentId ) ) )
					argb = argb & 0x00ffffff;
				else if ( activeSegment == segmentId )
					argb = argb & 0x00ffffff | activeSegmentAlpha;

				synchronized ( segmentARGBCache )
				{
					segmentARGBCache.put( segmentId, argb );
				}
			}
			if ( activeFragment == fragmentId && ( argb & 0xff000000 ) != 0 )
				argb = argb & 0x00ffffff | activeFragmentAlpha;

			synchronized ( fragmentARGBCache )
			{
				fragmentARGBCache.put( fragmentId, argb );
			}
		}

		return argb;
	}
}
