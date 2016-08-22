package bdv.util.sparse;

import net.imglib2.Localizable;
import net.imglib2.RandomAccessible;

public interface SparseRandomAccessible< T > extends RandomAccessible< T >
{

	public boolean hasData( final Localizable pos );

	public boolean hasData( final long[] pos );
}
