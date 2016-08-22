package bdv.util.sparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import ij.ImageJ;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Sampler;
import net.imglib2.algorithm.fill.Filter;
import net.imglib2.algorithm.fill.FloodFill;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;

public class MapSparseRandomAccessible< T extends Type< T > > implements SparseRandomAccessible< T >
{

	public static class HashableLongArray
	{
		private final long[] data;

		public HashableLongArray( final long[] data )
		{
			super();
			this.data = data;
		}

		@Override
		public int hashCode()
		{
			int hashCode = 1;
			for ( int i = 0; i < data.length; ++i )
			{
				hashCode = 31 * hashCode + Long.hashCode( data[ i ] );
			}
			return hashCode;
		}

		@Override
		public boolean equals( final Object o )
		{
			if ( o instanceof HashableLongArray ) {
				return Arrays.equals( data, ( ( HashableLongArray ) o ).data );
			} else if ( o instanceof long[] ) {
				return Arrays.equals( data, ( long[] ) o );
			}
			else
			{
				return false;
			}
		}

		public long[] getData()
		{
			return data;
		}

	}

	private final int numDimensions;

	private final ImgFactory< T > factory;

	private final long[] cellSize;

	private final T defaultValue;

	private final Map< HashableLongArray, RandomAccessibleInterval< T > > store;

	private final Map< HashableLongArray, RandomAccess< T > > accesses;

	public MapSparseRandomAccessible(
			final ImgFactory< T > factory,
			final long[] cellSize,
			final T defaultValue )
	{
		this( factory, cellSize, defaultValue, new HashMap<>() );
	}

	public MapSparseRandomAccessible(
			final ImgFactory< T > factory,
			final long[] cellSize,
			final T defaultValue,
			final Map< HashableLongArray, RandomAccessibleInterval< T > > store )
	{
		super();
		this.numDimensions = cellSize.length;
		this.factory = factory;
		this.cellSize = cellSize;
		this.defaultValue = defaultValue;
		this.store = store;
		this.accesses = new HashMap<>();
		for ( final Entry< HashableLongArray, RandomAccessibleInterval< T > > entry : this.store.entrySet() )
		{
			this.accesses.put( entry.getKey(), entry.getValue().randomAccess() );
		}
	}

	@Override
	public RandomAccess< T > randomAccess()
	{
		return new SparseRandomAccess();
	}

	@Override
	public RandomAccess< T > randomAccess( final Interval interval )
	{
		return randomAccess();
	}

	@Override
	public int numDimensions()
	{
		return numDimensions;
	}

	@Override
	public boolean hasData( final Localizable pos )
	{
		final long[] local = new long[ numDimensions ];
		pos.localize( local );
		globalToMap( local, local, cellSize );
		return store.containsKey( local );
	}

	@Override
	public boolean hasData( final long[] pos )
	{
		final long[] local = pos.clone();
		globalToMap( pos, local, cellSize );
		return store.containsKey( local );
	}

	private static void globalToMap( final long[] global, final long[] mapIndices, final long[] cellSize )
	{
		for ( int d = 0; d < global.length; ++d )
		{
			final long g = global[ d ];
			final long add = g < 0 ? 1 : 0;
			mapIndices[ d ] = ( g + add ) / cellSize[ d ] + ( g < 0 ? -add : 0 );
		}
	}

	public class SparseRandomAccess extends Point implements RandomAccess< T >
	{

		private final long[] local;

		public SparseRandomAccess()
		{
			this( new long[ numDimensions ] );
		}

		public SparseRandomAccess( final long[] position )
		{
			super( position );
			this.local = new long[ numDimensions ];
		}

		@Override
		public T get()
		{
			globalToMap( position, local, cellSize );
			final HashableLongArray hLocal = new HashableLongArray( local.clone() );
			RandomAccess< T > access = accesses.get( hLocal );
			if ( access == null )
			{
				final long[] translation = new long[ numDimensions ];
				for ( int d = 0; d < numDimensions; ++d )
				{
					translation[ d ] = local[ d ] * cellSize[ d ];
				}
				final Img< T > img = factory.create( cellSize, defaultValue );
				for ( final T i : img ) {
					i.set( defaultValue );
				}
				final RandomAccessibleInterval< T > translatedImg = Views.translate( img, translation );
				access = translatedImg.randomAccess();
				store.put( hLocal, translatedImg );
				accesses.put( hLocal, access );
			}
//			System.out.println( Arrays.toString( local ) + " " + Arrays.toString( position ) );
//			if ( local[ 0 ] == -2 )
//			{
//				final long[] min = new long[ numDimensions ];
//				final long[] max = new long[ numDimensions ];
//				store.get( new HashableLongArray( local ) ).min( min );
//				store.get( new HashableLongArray( local ) ).max( max );
//				System.out.println( Arrays.toString( min ) + " " + Arrays.toString( max ) );
//			}

			access.setPosition( position );

			return access.get();
		}

		@Override
		public Sampler< T > copy()
		{
			return copyRandomAccess();
		}

		@Override
		public RandomAccess< T > copyRandomAccess()
		{
			return new SparseRandomAccess( position.clone() );
		}

	}

	public static void main( final String[] args )
	{
		final long[] cellSize = new long[] { 4, 8 };
		final ImgFactory< LongType > factory = new ArrayImgFactory<>();
		final HashMap< HashableLongArray, RandomAccessibleInterval< LongType > > hm = new HashMap<>();
		final LongType defaultValue = new LongType( 3 );
		final MapSparseRandomAccessible< LongType > accessible = new MapSparseRandomAccessible<>( factory, cellSize, defaultValue, hm );

		final Random rng = new Random( 100 );

		final ArrayList< long[] > pos = new ArrayList<>();
		final RandomAccess< LongType > access = accessible.randomAccess();

		for ( int i = 0; i < 3; ++i )
		{
			final long x = rng.nextLong();
			final long y = rng.nextLong();
			long val = rng.nextLong();
			while ( val == defaultValue.get() )
			{
				val = rng.nextLong();
			}
			final long[] arr = new long[] { x, y };
			pos.add( arr );
			access.setPosition( arr );
			access.get().set( val );
			System.out.println( x + "," + y + " " + val );
		}
		System.out.println( "" );

		for ( final HashableLongArray h : hm.keySet() )
		{
			System.out.println( Arrays.toString( h.getData() ) );
		}

		System.out.println();

		for ( final long[] p : pos )
		{
			access.setPosition( p );
			System.out.println( access.get() );
			access.fwd( 1 );
			System.out.println( access.get() );
		}

		final ArrayImg< IntType, IntArray > img = ArrayImgs.ints( 40, 30 );
		final Filter< Pair< IntType, LongType >, Pair< IntType, LongType > > filter = ( t, u ) -> t.getB().get() != 13 && t.getA().get() != 12;
		FloodFill.fill( Views.extendValue( img, new IntType( 12 ) ), accessible, new Point( 1, 2 ), new LongType( 13 ), new DiamondShape( 1 ), filter );

		new ImageJ();
		ImageJFunctions.show( Views.interval( accessible, new long[] { -5, -5 }, new long[] { 50, 40 } ) );

	}

}
