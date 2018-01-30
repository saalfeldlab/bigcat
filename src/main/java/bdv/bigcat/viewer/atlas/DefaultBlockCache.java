package bdv.bigcat.viewer.atlas;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.bigcat.viewer.viewer3d.NeuronFX.BlockListKey;
import bdv.labels.labelset.Label;
import gnu.trove.iterator.TLongIterator;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.set.hash.TLongHashSet;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

public class DefaultBlockCache implements Cache< BlockListKey, long[] >
{

	private final static Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	private final String baseDir;

	public DefaultBlockCache( final String baseDir )
	{
		super();
		this.baseDir = baseDir;
	}

	@Override
	public long[] getIfPresent( final BlockListKey key )
	{
		try
		{
			return get( key );
		}
		catch ( final ExecutionException e )
		{
			throw new RuntimeException( e );
		}
	}

	@Override
	public void invalidateAll()
	{
		// TODO Auto-generated method stub

	}

	@Override
	public long[] get( final BlockListKey key ) throws ExecutionException
	{
		final File f = new File( new File( baseDir, Integer.toString( key.scaleIndex() ) ), Long.toString( key.id() ) );

		try (final FileInputStream fis = new FileInputStream( f ))
		{
			final int numElements = ( int ) f.length();
			final byte[] bytes = new byte[ numElements ];
			final BufferedInputStream bufferedStream = new BufferedInputStream( fis );
			bufferedStream.read( bytes );
			final long[] blockList = new long[ bytes.length / Long.BYTES ];
			final ByteBuffer bb = ByteBuffer.wrap( bytes );
			for ( int i = 0; i < blockList.length; ++i )
				blockList[ i ] = bb.getLong();
			return blockList;
		}
		catch ( final IOException e )
		{
			throw new ExecutionException( e );
		}
	}

	public static void generateData(
			final List< RandomAccessibleInterval< UnsignedLongType > > labels,
			final int[][] blockSizes,
			final String dir,
			final ExecutorService es,
			final int numTasks ) throws IOException, InterruptedException, ExecutionException
	{

		final Path tmpDir = Files.createTempDirectory( "bigcat-default-block-cache" );

		LOG.info( "Storing block locations at {}", tmpDir );

		final List< Future< Void > > reduceTasks = new ArrayList<>();

		for ( int level = labels.size() - 1; level >= 0; --level )
		{
			final File subDir = new File( tmpDir.toFile(), "" + level );
			subDir.mkdirs();
			final RandomAccessibleInterval< UnsignedLongType > label = labels.get( level );
			final int[] blockSize = blockSizes[ level ];

			final List< Interval > blocks = collectAllOffsets(
					Intervals.minAsLongArray( label ),
					Intervals.maxAsLongArray( label ),
					blockSizes[ level ],
					min -> {
						final long[] max = min.clone();
						for ( int d = 0; d < max.length; ++d )
							max[ d ] = Math.min( min[ d ] + blockSize[ d ] - 1, label.max( d ) );
						return new FinalInterval( min, max );
					} );

			final int taskSize = Math.max( blocks.size() / numTasks, 1 );
			final int actualNumberOfTasks = ( int ) Math.ceil( blocks.size() / ( 1.0 * taskSize ) );
			final CountDownLatch countDown = new CountDownLatch( actualNumberOfTasks );
			final AtomicInteger startedTasks = new AtomicInteger();

			for ( int taskStart = 0; taskStart < blocks.size(); taskStart += taskSize )
			{
				final List< Interval > subList = blocks.subList( taskStart, Math.min( taskStart + taskSize, blocks.size() ) );
				final Callable< Void > task = () -> {
					final String initialName = Thread.currentThread().getName();
					try
					{
						Thread.currentThread().setName( initialName + " -- generating label-block mapping " + tmpDir.toString() );
						final int taskNumber = startedTasks.getAndIncrement();
						LOG.debug( "Added task number {} for {} blocks", taskNumber, subList.size() );
						final byte[] data = new byte[ label.numDimensions() * 2 * Long.BYTES ];
						final ByteBuffer bb = ByteBuffer.wrap( data );
						final TLongHashSet containedLabels = new TLongHashSet();
						for ( final Interval block : subList )
						{
							containedLabels.clear();
							for ( final UnsignedLongType l : Views.interval( label, block ) )
							{
								final long id = l.get();
								if ( Label.regular( id ) )
									containedLabels.add( id );
							}
							LOG.trace( "Found {} labels in interval {}", containedLabels.size(), block );

							for ( final TLongIterator it = containedLabels.iterator(); it.hasNext(); )
							{
								final long l = it.next();
								final File subSubDir = new File( subDir, "" + l );
								subSubDir.mkdirs();
								bb.position( 0 );
								Arrays.stream( Intervals.minAsLongArray( block ) ).forEach( bb::putLong );
								Arrays.stream( Intervals.maxAsLongArray( block ) ).forEach( bb::putLong );;
								final StringBuilder sb = new StringBuilder();
								final long[] m = Intervals.minAsLongArray( block );
								final long[] M = Intervals.minAsLongArray( block );
								sb
										.append( m[ 0 ] ).append( "," ).append( m[ 1 ] ).append( "," ).append( m[ 2 ] ).append( "," )
										.append( M[ 0 ] ).append( "," ).append( M[ 1 ] ).append( "," ).append( M[ 2 ] );
								final File f = new File( subSubDir, sb.toString() );
								f.createNewFile();
								try (FileOutputStream fos = new FileOutputStream( f ))
								{
									fos.write( data );
								}
							}
						}
					}
					finally
					{
						synchronized ( countDown )
						{
							countDown.countDown();
							LOG.debug( "Finished task -- {} to go.", countDown.getCount() );
						}
						Thread.currentThread().setName( initialName );
					}
					return null;
				};
				es.submit( task );
			}

			LOG.debug( "Submitted {} block location generation tasks for {} blocks.", actualNumberOfTasks, blocks.size() );

			countDown.await();

			LOG.info( "Done with generating block locations for level {}.", level );

			final File targetSubDir = new File( dir, "" + level );
			targetSubDir.mkdirs();
			final String[] files = subDir.list();
			for ( final String file : files )
			{
				final Callable< Void > task = () -> {
					final File srcFile = new File( subDir, file );
					final File tgtFile = new File( targetSubDir, file );
					tgtFile.createNewFile();

					final TLongArrayList intervals = new TLongArrayList();
					for ( final String f : srcFile.list() )
						try (FileInputStream fis = new FileInputStream( new File( srcFile, f ) ))
						{
							final byte[] data = new byte[ 2 * 3 * Long.BYTES ];
							fis.read( data );
							final ByteBuffer bb = ByteBuffer.wrap( data );
							for ( int i = 0; i < 6; ++i )
								intervals.add( bb.getLong() );
						}

					try (FileOutputStream fos = new FileOutputStream( tgtFile ))
					{
						final byte[] data = new byte[ intervals.size() * Long.BYTES ];
						final ByteBuffer bb = ByteBuffer.wrap( data );
						for ( int i = 0; i < intervals.size(); ++i )
							bb.putLong( intervals.get( i ) );
						fos.write( data );
					}

					return null;
				};
				reduceTasks.add( es.submit( task ) );
			}

		}
		for ( final Future< Void > f : reduceTasks )
			f.get();

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

}
