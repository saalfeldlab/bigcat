package bdv.bigcat.viewer.viewer3d.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.stream.IntStream;

import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.viewer3d.NeuronFX.ShapeKey;
import bdv.bigcat.viewer.viewer3d.util.HashWrapper;
import gnu.trove.set.hash.TLongHashSet;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.CacheLoader;
import net.imglib2.converter.Converter;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.logic.BoolType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;

public class CacheUtils
{

	public static < D, T > CacheLoader< HashWrapper< long[] >, long[] >[] uniqueLabelLoaders(
			final DataSource< D, T > source,
			final int[][] blockSizes,
			final BiConsumer< D, TLongHashSet > collectLabels )
	{
		final int numMipmapLevels = source.getNumMipmapLevels();
		assert blockSizes.length == numMipmapLevels;

		@SuppressWarnings( "unchecked" )
		final CacheLoader< HashWrapper< long[] >, long[] >[] loaders = new CacheLoader[ numMipmapLevels ];

		for ( int level = 0; level < numMipmapLevels; ++level )
		{
			final RandomAccessibleInterval< D > data = source.getDataSource( 0, level );
			final boolean isZeroMin = Arrays.stream( Intervals.minAsLongArray( data ) ).filter( m -> m != 0 ).count() == 0;
			final CellGrid grid = new CellGrid( Intervals.dimensionsAsLongArray( data ), blockSizes[ level ] );
			loaders[ level ] = new UniqueLabelListCacheLoader<>( isZeroMin ? data : Views.zeroMin( data ), grid, collectLabels );
		}
		return loaders;
	}

	public static < D, T > CacheLoader< Long, Interval[] >[] blocksForLabelLoaders(
			final DataSource< D, T > source,
			final CacheLoader< HashWrapper< long[] >, long[] >[] uniqueLabelLoaders,
			final int[][] blockSizes,
			final double[][] scalingFactors,
			final ExecutorService es )
	{
		final int numMipmapLevels = source.getNumMipmapLevels();
		assert uniqueLabelLoaders.length == numMipmapLevels;

		@SuppressWarnings( "unchecked" )
		final CacheLoader< Long, Interval[] >[] loaders = new CacheLoader[ numMipmapLevels ];

		for ( int level = numMipmapLevels - 1; level >= 0; --level )
		{
			final Interval interval = source.getDataSource( 0, level );
			final long[] dims = Intervals.dimensionsAsLongArray( interval );
			final long[] max = Arrays.stream( dims ).map( v -> v - 1 ).toArray();
			final int[] bs = blockSizes[ level ];
			final CellGrid grid = new CellGrid( dims, bs );
			final int finalLevel = level;
			loaders[ level ] = new BlocksForLabelCacheLoader(
					grid,
					level == numMipmapLevels - 1 ? l -> new Interval[] { new FinalInterval( dims.clone() ) } : wrapAsFunction( loaders[ level + 1 ] ),
					level == numMipmapLevels - 1 ? l -> collectAllOffsets( dims, bs, b -> fromMin( b, max, bs ) ) : relevantBlocksFromLowResInterval( grid, scalingFactors[ level + 1 ], scalingFactors[ level ] ),
					wrapAsFunction( key -> uniqueLabelLoaders[ finalLevel ].get( HashWrapper.longArray( key ) ) ),
					es );
		}

		return loaders;
	}

	public static Function< Interval, List< Interval > > relevantBlocksFromLowResInterval(
			final CellGrid grid,
			final double[] lowerResScalingFactors,
			final double[] higherResScalingFactors )
	{
		final double[] scalingFactors = IntStream.range( 0, lowerResScalingFactors.length ).mapToDouble( d -> lowerResScalingFactors[ d ] / higherResScalingFactors[ d ] ).toArray();
		return interval -> {
			final long[] min = Intervals.minAsLongArray( interval );
			final long[] max = Intervals.maxAsLongArray( interval );
			final long[] intervalMax = grid.getImgDimensions();
			final int[] blockSize = new int[ min.length ];
			for ( int d = 0; d < min.length; ++d )
			{
				min[ d ] = ( long ) Math.floor( min[ d ] * scalingFactors[ d ] / grid.cellDimension( d ) ) * grid.cellDimension( d );
				max[ d ] = ( long ) Math.ceil( max[ d ] * scalingFactors[ d ] / grid.cellDimension( d ) ) * grid.cellDimension( d );
				blockSize[ d ] = grid.cellDimension( d );
				intervalMax[ d ] -= 1;
			}
			return collectAllOffsets( min, max, blockSize, b -> fromMin( b, intervalMax, blockSize ) );
		};
	}

	public static < D, T > Cache< ShapeKey, Pair< float[], float[] > >[] meshCacheLoaders(
			final DataSource< D, T > source,
			final int[][] cubeSizes,
			final LongFunction< Converter< D, BoolType > > getMaskGenerator,
			final Function< CacheLoader< ShapeKey, Pair< float[], float[] > >, Cache< ShapeKey, Pair< float[], float[] > > > makeCache )
	{
		final int numMipmapLevels = source.getNumMipmapLevels();
		@SuppressWarnings( "unchecked" )
		final Cache< ShapeKey, Pair< float[], float[] > >[] caches = new Cache[ numMipmapLevels ];

		for ( int i = 0; i < numMipmapLevels; ++i )
		{
			final AffineTransform3D transform = new AffineTransform3D();
			source.getSourceTransform( 0, i, transform );
			final MeshCacheLoader< D > loader = new MeshCacheLoader<>(
					cubeSizes[ i ],
					source.getDataSource( 0, i ),
					getMaskGenerator,
					transform );
			final Cache< ShapeKey, Pair< float[], float[] > > cache = makeCache.apply( loader );
			loader.setGetHigherResMesh( wrapAsFunction( cache ) );
			caches[ i ] = cache;
		}

		return caches;

	}

	public static < T, U > Function< T, U > wrapAsFunction( final Cache< T, U > throwingCache )
	{
		return t -> {
			try
			{
				return throwingCache.get( t );
			}
			catch ( final Exception e )
			{
				throw new RuntimeException( e );
			}
		};
	}

	public static < T, U > Function< T, U > wrapAsFunction( final CacheLoader< T, U > throwingLoader )
	{
		return t -> {
			try
			{
				return throwingLoader.get( t );
			}
			catch ( final Exception e )
			{
				throw new RuntimeException( e );
			}
		};
	}

	public static List< long[] > collectAllOffsets( final long[] dimensions, final int[] blockSize )
	{
		return collectAllOffsets( dimensions, blockSize, block -> block );
	}

	public static < T > List< T > collectAllOffsets( final long[] dimensions, final int[] blockSize, final Function< long[], T > func )
	{
		return collectAllOffsets( new long[ dimensions.length ], Arrays.stream( dimensions ).map( d -> d - 1 ).toArray(), blockSize, func );
	}

	public static List< long[] > collectAllOffsets( final long[] min, final long[] max, final int[] blockSize )
	{
		return collectAllOffsets( min, max, blockSize, block -> block );
	}

	public static < T > List< T > collectAllOffsets( final long[] min, final long[] max, final int[] blockSize, final Function< long[], T > func )
	{
		final List< T > blocks = new ArrayList<>();
		final int nDim = min.length;
		final long[] offset = min.clone();
		for ( int d = 0; d < nDim; )
		{
			final long[] target = offset.clone();
			blocks.add( func.apply( target ) );
			for ( d = 0; d < nDim; ++d )
			{
				offset[ d ] += blockSize[ d ];
				if ( offset[ d ] <= max[ d ] )
					break;
				else
					offset[ d ] = 0;
			}
		}
		return blocks;
	}

	public static < T > T[] asArray( final Collection< T > collection, final IntFunction< T[] > generator )
	{
		return collection.stream().toArray( generator );
	}

	public static Interval fromMin( final long[] min, final long[] intervalMax, final int[] blockSize )
	{
		final long[] max = new long[ min.length ];
		for ( int d = 0; d < max.length; ++d )
			max[ d ] = Math.min( min[ d ] + blockSize[ d ] - 1, intervalMax[ d ] );
		return new FinalInterval( min, max );
	}

}
