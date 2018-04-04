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
package bdv.bigcat.label;

import gnu.trove.set.hash.TLongHashSet;

/**
 * Assigns segments to an arbitrary property.
 *
 * TODO currently, this reproduces parts of the TLongSet interface which seems
 *   to make it a redundant implementation, however, I suspect this class to
 *   become a remote backed interface and not relying on the trove interface
 *   seems to be a more generic solution at this point...
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SegmentAssignment
{
	final private TLongHashSet segments = new TLongHashSet();

	public TLongHashSet getAssignedSegments()
	{
		return segments;
	}

	public void add( final long segmentId )
	{
		segments.add( segmentId );
	}

	public boolean remove( final long segmentId )
	{
		return segments.remove( segmentId );
	}

	public boolean contains( final long segmentId )
	{
		return segments.contains( segmentId );
	}
}
