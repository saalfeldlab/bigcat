package bdv.bigcat.control;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;

import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.ClickBehaviour;
import org.scijava.ui.behaviour.InputTriggerAdder;
import org.scijava.ui.behaviour.InputTriggerMap;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import com.google.protobuf.ByteString;

import bdv.bigcat.control.SolverMessages.Start.Builder;
import bdv.bigcat.label.FragmentSegmentAssignment;
import bdv.img.h5.H5Utils;
import bdv.labels.labelset.Label;
import bdv.labels.labelset.LabelMultisetType;
import bdv.labels.labelset.VolatileLabelMultisetType;
import bdv.util.IdService;
import bdv.util.sparse.ConstantMapSparseRandomAccessible;
import bdv.util.sparse.MapSparseRandomAccessible;
import bdv.util.sparse.MapSparseRandomAccessible.HashableLongArray;
import bdv.viewer.ViewerPanel;
import gnu.trove.TLongCollection;
import gnu.trove.iterator.TLongIterator;
import gnu.trove.iterator.TLongObjectIterator;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.hash.TLongHashSet;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.IterableInterval;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccess;
import net.imglib2.algorithm.fill.Filter;
import net.imglib2.algorithm.fill.FloodFill;
import net.imglib2.algorithm.fill.TypeWriter;
import net.imglib2.algorithm.fill.Writer;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.algorithm.neighborhood.DiamondShape.NeighborhoodsAccessible;
import net.imglib2.algorithm.neighborhood.Neighborhood;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.BooleanType;
import net.imglib2.type.NativeType;
import net.imglib2.type.Type;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;

/**
 * Persist fragment segment assignments, painted labels, viewer state, and
 * flattened labels.
 *
 * TODO WORKS ONLY AT HIGHEST RESOLUTION RIGHT NOW!!!!
 *
 * @author Philipp Hanslovsky
 */
public class SendPaintedLabelsToSolver
{

	public final class AnnotationTask implements Comparable< AnnotationTask >
	{
		private final String uuid;

		private final long id;

		private final TLongCollection newIds;

		private final TLongCollection invalidatedIds;

		private final TLongObjectHashMap< ConstantMapSparseRandomAccessible< BitType > > binaryMasks;

		private final long[] min;

		private final long[] max;

		public AnnotationTask(
				final String uuid,
				final long id,
				final TLongCollection newIds,
				final TLongCollection completelyRemovedIds,
				final TLongObjectHashMap< ConstantMapSparseRandomAccessible< BitType > > binaryMasks,
				final long[] min,
				final long[] max )
		{
			super();
			this.uuid = uuid;
			this.id = id;
			this.newIds = newIds;
			this.invalidatedIds = completelyRemovedIds;
			this.binaryMasks = binaryMasks;
			this.min = min;
			this.max = max;
		}

		public int hashChode()
		{
			return uuid.hashCode();
		}

		public boolean Equals( final Object obj )
		{
			return uuid.equals( obj );
		}

		@Override
		public int compareTo( final AnnotationTask o )
		{
			return Long.compare( id, o.id );
		}

	}

	final private ViewerPanel viewer;

	final private RandomAccessibleInterval< LabelMultisetType > labelMultisetSource;

	final private RandomAccessibleInterval< VolatileLabelMultisetType > volatileLabelMultisetSource;

	final private RandomAccessibleInterval< LongType > labelSource;

	final private AffineTransform3D labelTransform;

	// for behavioUrs
	private final BehaviourMap behaviourMap = new BehaviourMap();

	private final InputTriggerMap inputTriggerMap = new InputTriggerMap();

	private final InputTriggerAdder inputAdder;

	private final HashSet< AnnotationTask > activeAnnotationTasks;

	private final PriorityQueue< AnnotationTask > annotationTaskQueue;

	private final IdService idService;

	private final TLongHashSet invalidIds;

	private final TLongHashSet newIdsCurrentlyAtSolver;

	private final FragmentSegmentAssignment assignment;

	public BehaviourMap getBehaviourMap()
	{
		return behaviourMap;
	}

	public InputTriggerMap getInputTriggerMap()
	{
		return inputTriggerMap;
	}

	private final int[] cellDimensions;

	private final int[] h5CellDimensions;

	private final File h5File;

	private final String mergedLabelsDataset;

	private final Socket socket;

	public SendPaintedLabelsToSolver(
			final ViewerPanel viewer,
			final RandomAccessibleInterval< LabelMultisetType > labelMultisetSource,
			final RandomAccessibleInterval< VolatileLabelMultisetType > volatileLabelMultisetSource,
			final RandomAccessibleInterval< LongType > labelSource,
			final AffineTransform3D labelTransform,
			final int[] cellDimensions,
			final int[] h5CellDimensions,
			final InputTriggerConfig config,
			final Socket socket,
			final IdService idService,
			final FragmentSegmentAssignment assignment,
			final File h5File,
			final String mergedLabelsDataset )
	{
		this.viewer = viewer;
		this.labelMultisetSource = labelMultisetSource;
		this.volatileLabelMultisetSource = volatileLabelMultisetSource;
		this.labelSource = labelSource;
		this.labelTransform = labelTransform;
		this.cellDimensions = cellDimensions;
		this.h5CellDimensions = h5CellDimensions;
		this.inputAdder = config.inputTriggerAdder( inputTriggerMap, "Solver" );
		this.socket = socket;
		this.activeAnnotationTasks = new HashSet<>();
		this.annotationTaskQueue = new PriorityQueue<>();
		this.idService = idService;
		this.invalidIds = new TLongHashSet();
		this.newIdsCurrentlyAtSolver = new TLongHashSet();
		this.assignment = assignment;
		this.h5File = h5File;
		this.mergedLabelsDataset = mergedLabelsDataset;

		new Thread( () -> {
			try
			{
				checkQueueAndSend();
			}
			catch ( final InterruptedException e )
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} ).start();

		new SendLabels( "send painted label to solver", "ctrl shift A button1" ).register();
		System.out.println( "Finished constructor..." );
	}

	private abstract class SelfRegisteringBehaviour implements Behaviour
	{
		private final String name;

		private final String[] defaultTriggers;

		protected String getName()
		{
			return name;
		}

		public SelfRegisteringBehaviour( final String name, final String... defaultTriggers )
		{
			this.name = name;
			this.defaultTriggers = defaultTriggers;
		}

		public void register()
		{
			behaviourMap.put( name, this );
			inputAdder.put( name, defaultTriggers );
		}
	}

	private void checkQueueAndSend() throws InterruptedException
	{
		while ( !Thread.currentThread().isInterrupted() )
		{
			Thread.sleep( 100 ); // sleep 0.1 seconds
//			System.out.println( "Checking for tasks..." );
			synchronized ( this.annotationTaskQueue )
			{
				if ( annotationTaskQueue.size() > 0 )
				{
					System.out.println( "Found task..." );
					final AnnotationTask annotationTask = annotationTaskQueue.peek();
					final HashSet< Long > allIds = new HashSet<>();
					for ( final TLongIterator it = annotationTask.newIds.iterator(); it.hasNext(); )
					{
						allIds.add( it.next() );
					}
//					for ( final TLongIterator it = annotationTask.neighboringIds.iterator(); it.hasNext(); )
//					{
//						allIds.add( it.next());
//					}
					final boolean isInConflict = false;
//					for ( final AnnotationTask task : activeAnnotationTasks )
//					{
//						for ( final long id : allIds )
//						{
//							if ( task.newIds.contains( id ) || task.neighboringIds.contains( id ) )
//							{
//								isInConflict = true;
//							}
//						}
//					}

					if ( isInConflict )
					{
//						System.out.println( "Task in conflict..." );
					}
					else
					{

						System.out.println( "Task not in conflict..." );
						annotationTaskQueue.poll();

						activeAnnotationTasks.add( annotationTask );
						for ( final TLongObjectIterator< ConstantMapSparseRandomAccessible< BitType > > binaryMaskIt = annotationTask.binaryMasks.iterator(); binaryMaskIt.hasNext(); )
						{
							binaryMaskIt.advance();
							final Builder startMessageBuilder = SolverMessages.Start.newBuilder();
							startMessageBuilder.setUuid( annotationTask.uuid );
							startMessageBuilder.setId( binaryMaskIt.key() );
							System.out.println( "Preparing message for " + binaryMaskIt.key() );
							for ( int d = 0; d < 3; ++d )
							{
								startMessageBuilder.addMin( annotationTask.min[ d ] );
								startMessageBuilder.addMax( annotationTask.max[ d ] );
							}

							for ( final TLongIterator it = annotationTask.newIds.iterator(); it.hasNext(); )
							{
								startMessageBuilder.addContainedIds( it.next() );
							}
//						for ( final TLongIterator it = annotationTask.neighboringIds.iterator(); it.hasNext(); )
//						{
//							startMessageBuilder.addNeighboringIds( it.next() );
//						}
							for ( final TLongIterator it = annotationTask.invalidatedIds.iterator(); it.hasNext(); )
							{
								startMessageBuilder.addCompletelyRemovedIds( it.next() );
							}

							final SolverMessages.Start startMessage = startMessageBuilder.build();

							socket.send( SolverMessages.Wrapper.newBuilder()
									.setType( SolverMessages.Type.START )
									.setStart( startMessage )
									.build().toByteArray(), ZMQ.SNDMORE );

							final int cellSize = cellDimensions[ 0 ] * cellDimensions[ 1 ] * cellDimensions[ 2 ];

							final byte[] bytes = new byte[ cellSize ];

							final ByteBuffer buffer = ByteBuffer.wrap( bytes );

							final byte ONE = ( byte ) 1;
							final byte ZERO = ( byte ) 0;

							final long[] currentMin = new long[ 3 ];
							final long[] currentMax = new long[ 3 ];

							for ( final Entry< HashableLongArray, RandomAccessibleInterval< BitType > > entry : binaryMaskIt.value().getStore().entrySet() )
							{
								final SolverMessages.Annotation.Builder annotationMessageBuilder = SolverMessages.Annotation.newBuilder();
								annotationMessageBuilder.setId( annotationTask.id );
								annotationMessageBuilder.setUuid( annotationTask.uuid );
								boolean sendMessage = false;
								buffer.rewind();

								final RandomAccessibleInterval< BitType > rai = entry.getValue();
								rai.min( currentMin );
								rai.max( currentMax );

								for ( int d = 0; d < currentMin.length; ++d )
								{
									annotationMessageBuilder.addMin( currentMin[ d ] );
									annotationMessageBuilder.addMax( currentMax[ d ] );
								}

//							final IntervalView< BitType > cell = Views.interval(
//									target,
//									currentMin,
//									currentMax );
								final IterableInterval< BitType > cell = Views.iterable( entry.getValue() );
								for ( final BitType bit : cell )
								{
									if ( bit.get() )
									{
										buffer.put( ONE );
										sendMessage = true;
									}
									else
									{
										buffer.put( ZERO );
									}
								}

								if ( sendMessage )
								{
									annotationMessageBuilder.setData( ByteString.copyFrom( bytes ) );
									System.out.println( "Sending message! " + binaryMaskIt.key() );
//								socket.send( bytes, ZMQ.SNDMORE );
									socket.send( SolverMessages.Wrapper.newBuilder()
											.setType( SolverMessages.Type.ANNOTATION )
											.setAnnotation( annotationMessageBuilder )
											.build().toByteArray(), ZMQ.SNDMORE );
								}
							}

//						socket.send( "stop" );
							socket.send( SolverMessages.Wrapper.newBuilder()
									.setType( SolverMessages.Type.STOP )
									.setStop( SolverMessages.Stop.newBuilder().setUuid( annotationTask.uuid ).build() )
									.build().toByteArray(), 0 );
						}
					}

				}
			}
		}
	}

	private < P extends RealLocalizable & RealPositionable > void setCoordinates( final P labelLocation, final int x, final int y )
	{
		labelLocation.setPosition( x, 0 );
		labelLocation.setPosition( y, 1 );
		labelLocation.setPosition( 0, 2 );

		viewer.displayToGlobalCoordinates( labelLocation );

		labelTransform.applyInverse( labelLocation, labelLocation );
	}

	private class SendLabels extends SelfRegisteringBehaviour implements ClickBehaviour
	{
		public SendLabels( final String name, final String... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		@Override
		public void click( final int x, final int y )
		{

			System.out.println( "Sending Labels!" );

			synchronized ( viewer )
			{
				final RealRandomAccess< LongType > paintAccess =
						Views.interpolate( Views.extendValue( labelSource, new LongType( Label.OUTSIDE ) ), new NearestNeighborInterpolatorFactory<>() ).realRandomAccess();
				setCoordinates( paintAccess, x, y );
				viewer.setCursor( java.awt.Cursor.getPredefinedCursor( java.awt.Cursor.WAIT_CURSOR ) );

				final LongType label = paintAccess.get();
				final long labelLong = label.get();
				System.out.println( labelLong + " " + paintAccess );

				if ( labelLong != Label.TRANSPARENT && labelLong != Label.INVALID && labelLong != Label.OUTSIDE )
				{
					System.out.println( "YEP!" );
					System.out.println( labelLong + " " + paintAccess );
					final long[] initialMin = new long[ 3 ];
					final long[] initialMax = new long[ 3 ];
					final Point seed = new Point( 3 );

					for ( int d = 0; d < 3; ++d )
					{
						final long pos = ( long ) paintAccess.getDoublePosition( d );
						final long min = pos % cellDimensions[ d ];
						initialMin[ d ] = min;
						initialMax[ d ] = min + cellDimensions[ d ] - 1;
						seed.setPosition( pos, d );
					}

					final long[] cellDimensionsLong = new long[] { cellDimensions[ 0 ], cellDimensions[ 1 ], cellDimensions[ 2 ] };

					final HashMap< HashableLongArray, RandomAccessibleInterval< BitType > > binaryMaskStore = new HashMap<>();
					final MapSparseRandomAccessible< BitType > binaryMaskForLabel =
							new MapSparseRandomAccessible<>( new ArrayImgFactory<>(), cellDimensionsLong, new BitType( false ), binaryMaskStore );

					final ExtendedRandomAccessibleInterval< LongType, RandomAccessibleInterval< LongType > > source =
							Views.extendValue( labelSource, new LongType( Label.OUTSIDE ) );

					final BitType fillLabel = new BitType( true );
					final DiamondShape shape = new DiamondShape( 1 );
					final Filter< Pair< LongType, BitType >, Pair< LongType, BitType > > filter = ( lb1, lb2 ) -> {
						return !lb1.getB().get() && lb1.getA().valueEquals( lb2.getA() );
					};
					System.out.println( "Start fill" );
					FloodFill.fill( source, binaryMaskForLabel, seed, fillLabel, shape, filter );
					System.out.println( "Stop fill" );


					// find "affected" fragments
					final TLongHashSet labelsWithinMask = new TLongHashSet();
					final TLongHashSet labelsBorderingMask = new TLongHashSet();
					// do we need innerPoints
//					final TLongObjectHashMap< long[] > innerPoints = new TLongObjectHashMap<>();
					final TLongObjectHashMap< ArrayList< long[] > > edgePointsOutside = new TLongObjectHashMap<>();

					collectContainedAndNeighboringIdsAndEdgeLocations(
							binaryMaskForLabel.constantView(),
							labelMultisetSource,
							labelsWithinMask,
							labelsBorderingMask,
							edgePointsOutside );

					final TLongObjectHashMap< TLongObjectHashMap< MapSparseRandomAccessible< BitType > > > newConnectedComponents = new TLongObjectHashMap<>();

					createBinaryMasksForNewFragments(
							binaryMaskForLabel.constantView(),
							labelMultisetSource,
							labelsWithinMask,
							edgePointsOutside,
							newConnectedComponents,
							idService,
							cellDimensionsLong,
							new BitType() );


					final TLongHashSet overPainted = new TLongHashSet();

					System.out.println( "BLUB" );

					for ( final TLongIterator innerIterator = labelsWithinMask.iterator(); innerIterator.hasNext(); )
					{
						final long in = innerIterator.next();
						System.out.println( in );
						if ( !labelsBorderingMask.contains( in ) )
						{
							overPainted.add( in );
//							ArrayList< Long > al = oldFragmentsToNewFragments.get( in );
//							if ( al == null )
//							{
//								al = new ArrayList<>();
//								oldFragmentsToNewFragments.put( in, al );
//							}
//							al.add( labelLong );
						}
					}

					System.out.println( "BLEB" );

					final TLongObjectHashMap< ConstantMapSparseRandomAccessible< BitType > > newIdsBinaryMasks =
							new TLongObjectHashMap<>();
					newIdsBinaryMasks.put( labelLong, binaryMaskForLabel.constantView() );
					paintNewConnectedComponent(
							new LongType( Label.TRANSPARENT ),
							binaryMaskForLabel.constantView(),
							labelSource,
							new LongType( Label.OUTSIDE ) );

					for ( final TLongObjectIterator< TLongObjectHashMap< MapSparseRandomAccessible< BitType > > > it = newConnectedComponents.iterator(); it.hasNext(); )
					{
						it.advance();
						for ( final TLongObjectIterator< MapSparseRandomAccessible< BitType > > idAndCc = it.value().iterator(); idAndCc.hasNext(); )
						{
							idAndCc.advance();
							System.out.println( "ADDING " + it.key() + " " + idAndCc.key() );
//							final long[] mm = new long[ 3 ];
//							final long[] MM = new long[ 3 ];
//							Arrays.fill( mm, Long.MAX_VALUE );
//							Arrays.fill( MM, Long.MIN_VALUE );
//							for ( final RandomAccessibleInterval< BitType > VV : idAndCc.value().getStore().values() )
//							{
//								for ( int d = 0; d < 3; ++d )
//								{
//									mm[ d ] = Math.min( mm[ d ], VV.min( d ) );
//									MM[ d ] = Math.max( MM[ d ], VV.max( d ) );
//								}
//							}
//							BdvFunctions.show( Views.interval( idAndCc.value().constantView(), new FinalInterval( mm, MM ) ), "" + idAndCc.key() );
							newIdsBinaryMasks.put( idAndCc.key(), idAndCc.value().constantView() );
//							paintNewConnectedComponent(
//									new LongType( idAndCc.key() ),
//									idAndCc.value().constantView(),
//									labelSource,
//									new LongType( Label.OUTSIDE ) );
						}
					}

					detachIds( newConnectedComponents.keySet(), assignment );
					assignment.detachFragment( labelLong );

//					H5Utils.saveUnsignedLongIfNotExisiting(
//							Converters.convert( labelMultisetSource, ( s, t ) -> {
//								t.set( s.entrySet().iterator().next().getElement().id() );
//							}, new LongType() ),
//							h5File,
//							mergedLabelsDataset,
//							h5CellDimensions );

					for ( final TLongObjectIterator< ConstantMapSparseRandomAccessible< BitType > > idAndCc = newIdsBinaryMasks.iterator(); idAndCc.hasNext(); )
					{
						idAndCc.advance();
						final long currentLabel = idAndCc.key();
						System.out.println( "Self-assigning " + currentLabel );
						assignment.assignFragments( currentLabel, currentLabel );

						final ConstantMapSparseRandomAccessible< BitType > mask = idAndCc.value();
						final Map< HashableLongArray, RandomAccessibleInterval< BitType > > store = mask.getStore();

						final RandomAccessible< Pair< LabelMultisetType, LongType > > backgroundAndPainted =
								Views.pair( labelMultisetSource, labelSource );

						final RandomAccessible< Pair< Pair< LabelMultisetType, LongType >, BitType > > labelsAndMask =
								Views.pair( backgroundAndPainted, mask );

						{
							final HashSet< HashableLongArray > entriesToBeRemoved = new HashSet<>();
							for ( final Entry< HashableLongArray, RandomAccessibleInterval< BitType > > entry : store.entrySet() )
							{
								boolean hasEntries = false;
								for ( final BitType val : Views.iterable( entry.getValue() ) )
								{
									if ( val.get() )
									{
										hasEntries = true;
										break;
									}
								}
								if ( !hasEntries )
								{
									entriesToBeRemoved.add( entry.getKey() );
								}
							}
							for ( final HashableLongArray key : entriesToBeRemoved )
							{
								store.remove( key );
							}
						}

						System.out.println( "label=" + currentLabel + ", count=" + store.size() );

						for ( final RandomAccessibleInterval< BitType > volume : store.values() )
						{

							final RandomAccessible< Pair< LabelMultisetType, BitType > > paired = Views.pair(
									Views.extendValue( labelMultisetSource, new LabelMultisetType() ),
									volume );

							final RandomAccessible< LongType > converted = Converters.convert( paired,
									( input, output ) -> {
										output.set( input.getB().get() ? currentLabel : input.getA().entrySet().iterator().next().getElement().id() );
									},
									new LongType() );

							for ( final VolatileLabelMultisetType vlm : Views.interval( volatileLabelMultisetSource, volume ) )
							{
								vlm.setValid( false );
							}


							if ( currentLabel != labelLong )
							{
								System.out.println( "Saving volume " + currentLabel + " " + Arrays.toString( Intervals.minAsIntArray( volume ) ) + " " + Arrays.toString( Intervals.dimensionsAsIntArray( volume ) ) );
							}
//							H5Utils.saveUnsignedLongAt( converted, volume, labelMultisetSource, h5File, mergedLabelsDataset, h5CellDimensions, currentLabel != labelLong );
							H5Utils.saveUnsignedLongAt( converted, volume, labelMultisetSource, h5File, mergedLabelsDataset, h5CellDimensions, currentLabel != labelLong );
//						?????
//						H5Utils.saveUnsignedLong( Views.offsetInterval( converted, labelMultisetSource ), h5File, mergedLabelsDataset, h5CellDimensions );
						}
//						paintNewConnectedComponent( new LabelMultisetType(), mask, labelMultisetSource, new LabelMultisetType() );
						System.out.println( "label=" + currentLabel + ", count=" + store.size() );
					}

					final long[] min = new long[ 3 ];
					final long[] max = new long[ 3 ];

					getMinMax( binaryMaskForLabel.constantView(), cellDimensionsLong, min, max );


					synchronized ( annotationTaskQueue )
					{
						final AnnotationTask task = new AnnotationTask(
								Long.toString( labelLong ),
								labelLong,
								labelsWithinMask,
								overPainted,
								newIdsBinaryMasks,
								min,
								max );
						annotationTaskQueue.add( task );
					}

					viewer.requestRepaint();

					viewer.setCursor( java.awt.Cursor.getPredefinedCursor( java.awt.Cursor.DEFAULT_CURSOR ) );
				}
			}
		}
	}

	private static < T extends BooleanType< T > > void collectContainedAndNeighboringIdsAndEdgeLocations(
			final ConstantMapSparseRandomAccessible< T > binaryMaskForLabel,
			final RandomAccessibleInterval< LabelMultisetType > labelMultisetSource,
			final TLongHashSet labelsWithinMask,
			final TLongHashSet labelsBorderingMask,
			final TLongObjectHashMap< ArrayList< long[] > > edgePointsOutside )
	{
		for ( final Entry< HashableLongArray, RandomAccessibleInterval< T > > entry : binaryMaskForLabel.getStore().entrySet() )
		{
			final RandomAccessibleInterval< T > volume = entry.getValue();
			final long[] entryMin = new long[ 3 ];
			final long[] entryMax = new long[ 3 ];
			volume.min( entryMin );
			volume.max( entryMax );
			System.out.println( "Current min: " + Arrays.toString( entryMin ) );
			System.out.println( "current max: " + Arrays.toString( entryMax ) );
			final FinalInterval fi = new FinalInterval( entryMin, entryMax );
			final RandomAccessible< Pair< LabelMultisetType, T > > paired =
					Views.pair( Views.extendValue( labelMultisetSource, new LabelMultisetType() ), binaryMaskForLabel );
			final NeighborhoodsAccessible< Pair< LabelMultisetType, T > > pairsWithNeighbors =
					new DiamondShape( 1 ).neighborhoodsRandomAccessible( paired );

			final Cursor< Pair< LabelMultisetType, T > > pairCursor = Views.interval( paired, fi ).cursor();
			final Cursor< Neighborhood< Pair< LabelMultisetType, T > > > neighborhoodCursor = Views.interval( pairsWithNeighbors, fi ).cursor();

			while ( pairCursor.hasNext() )
			{
				final Pair< LabelMultisetType, T > labelsAndMask = pairCursor.next();
				final Neighborhood< Pair< LabelMultisetType, T > > neighborhood = neighborhoodCursor.next();
				if ( labelsAndMask.getB().get() )
				{
					final LabelMultisetType labels = labelsAndMask.getA();
//					final long[] innerPoint = null;
					for ( final bdv.labels.labelset.Multiset.Entry< Label > l : labels.entrySet() )
					{
						final long currentLabel = l.getElement().id();
						labelsWithinMask.add( currentLabel );
					}

					for ( final Cursor< Pair< LabelMultisetType, T > > n = neighborhood.cursor(); n.hasNext(); )
					{
						final Pair< LabelMultisetType, T > neighborPair = n.next();
						if ( !neighborPair.getB().get() )
						{
							final LabelMultisetType multiset = neighborPair.getA();
							final long[] outerPoint = new long[ 3 ];
							n.localize( outerPoint );
							if ( multiset.size() > 0 )
							{
//							for ( final bdv.labels.labelset.Multiset.Entry< Label > l : neighborPair.getA().entrySet() )
//							{
//								final long currentLabel = l.getElement().id();
								final long currentLabel = multiset.entrySet().iterator().next().getElement().id();
								if ( currentLabel == 0 )
								{
//									continue;
									System.out.println( "Was soll das? " + Arrays.toString( outerPoint ) );
								}
								labelsBorderingMask.add( currentLabel );
								ArrayList< long[] > points = edgePointsOutside.get( currentLabel );
								if ( points == null )
								{
									points = new ArrayList<>();
									edgePointsOutside.put( currentLabel, points );
									System.out.println( "MEP" + currentLabel );
								}
								points.add( outerPoint );
							}
						}
					}
				}
			}
		}
	}

	private static < T extends BooleanType< T > & NativeType< T > > void createBinaryMasksForNewFragments(
			final ConstantMapSparseRandomAccessible< T > binaryMaskForLabel,
			final RandomAccessibleInterval< LabelMultisetType > labelMultisetSource,
			final TLongHashSet labelsWithinMask,
			final TLongObjectHashMap< ArrayList< long[] > > edgePointsOutside,
			final TLongObjectHashMap< TLongObjectHashMap< MapSparseRandomAccessible< T > > > newConnectedComponents,
			final IdService idService,
			final long[] cellDimensionsLong,
			final T type )
	{
		final RandomAccess< LabelMultisetType > labelMultisetSourceAccess = labelMultisetSource.randomAccess();

		final T fillValue = type.createVariable();
		fillValue.set( true );

		final T seedValue = type.createVariable();
		seedValue.set( false );

		for ( final TLongObjectIterator< ArrayList< long[] > > outerPointsIterator = edgePointsOutside.iterator(); outerPointsIterator.hasNext(); )
		{

			outerPointsIterator.advance();
			final long currentLabel = outerPointsIterator.key();

			if ( !labelsWithinMask.contains( currentLabel ) )
			{
				System.out.println( "True neighbor, not modified: " + currentLabel );
				continue;
			}
			System.out.println( "Modified: " + currentLabel );

			for ( final long[] outerPoint : outerPointsIterator.value() )
			{
				labelMultisetSourceAccess.setPosition( outerPoint );

				TLongObjectHashMap< MapSparseRandomAccessible< T > > childConnectedComponents =
						newConnectedComponents.get( currentLabel );
				if ( childConnectedComponents == null )
				{
					childConnectedComponents = new TLongObjectHashMap<>();
					newConnectedComponents.put( currentLabel, childConnectedComponents );
				}

				boolean isContained = false;
				for ( final MapSparseRandomAccessible< T > cc : childConnectedComponents.valueCollection() )
				{

					final RandomAccess< T > access = cc.randomAccess();
					access.setPosition( outerPoint );
					if ( access.get().get() )
					{
						isContained = true;
						break;
					}
				}

				if ( !isContained )
				{
					final long id = idService.next();
					System.out.println( "id=" + id + " currentLabel=" + currentLabel );
					final T defaultValue = type.createVariable();
					defaultValue.set( false );
					final MapSparseRandomAccessible< T > cc =
							new MapSparseRandomAccessible< >( new ArrayImgFactory< T >(), cellDimensionsLong, defaultValue );


					final RandomAccessible< Pair< LabelMultisetType, T > > multisetsWithBinaryMask =
							Views.pair(
									Views.extendValue( labelMultisetSource, new LabelMultisetType() ), binaryMaskForLabel );
					final LongType count = new LongType( 0 );
					final Filter< Pair< Pair< LabelMultisetType, T >, T >, Pair< Pair< LabelMultisetType, T >, T > > ft = ( t, u ) -> {
						final Pair< LabelMultisetType, T > p1 = t.getA();
//										if ( p1.getA().contains( currentLabel ) && !p1.getB().get() && !t.getB().get() )
//										{
						count.inc();
//										}
//										System.out.println( p1.getA().contains( oldLabel ) && !p1.getB().get() );
//										System.out.println( !t.getB().get() );
						return p1.getA().contains( currentLabel ) && !p1.getB().get() && !t.getB().get();
					};
					final ValuePair< LabelMultisetType, T > seedLabel = new ValuePair<>( new LabelMultisetType(), seedValue );
					FloodFill.fill( multisetsWithBinaryMask, cc, labelMultisetSourceAccess, seedLabel, fillValue, new DiamondShape( 1 ), ft );

					System.out.println( count );

					childConnectedComponents.put( id, cc );
				}

//								if ( !outerPoints.containsKey( currentLabel ) )
//								{
//									if ( outerPoint == null )
//									{
//										outerPoint = new long[ 3 ];
//										pairCursor.localize( outerPoint );
//									}
//								}
//								outerPoints.put( currentLabel, outerPoint );
			}
		}
	}

	private static < T extends Type< T > > void getMinMax(
			final ConstantMapSparseRandomAccessible< T > store,
			final long[] cellDimension,
			final long[] min,
			final long[] max )
	{
		for ( int d = 0; d < min.length; ++d ) {
			min[d] = Long.MAX_VALUE;
			max[d] = Long.MIN_VALUE;
		}
		for ( final HashableLongArray key : store.getStore().keySet() )
		{
			final long[] pos = key.getData();
			for ( int d = 0; d < min.length; ++d ) {
				min[ d ] = Math.min( pos[ d ], min[ d ] );
				max[ d ] = Math.max( pos[ d ], max[ d ] );
			}
		}

		for ( int d = 0; d < min.length; ++d )
		{
			final long dim = cellDimension[ d ];
			min[ d ] *= dim;
			max[ d ] = max[ d ] * dim + dim - 1;
		}

	}

	private static < T extends BooleanType< T >, L extends Type< L > > void paintNewConnectedComponent(
			final L label,
			final ConstantMapSparseRandomAccessible< T > mask,
			final RandomAccessibleInterval< L > labelSource,
			final L extensionValue )
	{
		paintNewConnectedComponent( label, mask, labelSource, extensionValue, new TypeWriter<>() );
	}

	private static < T extends BooleanType< T >, L extends Type< L > > void paintNewConnectedComponent(
			final L label,
			final ConstantMapSparseRandomAccessible< T > mask,
			final RandomAccessibleInterval< L > labelSource,
			final L extensionValue,
			final Writer< L > writer )
	{
		final RandomAccessible< Pair< T, L > > paired = Views.pair( mask, Views.extendValue( labelSource, extensionValue ) );
		for ( final RandomAccessibleInterval< T > volume : mask.getStore().values() )
		{
			for ( final Pair< T, L > p : Views.interval( paired, volume ) )
			{
				if ( p.getA().get() )
				{
					writer.write( label, p.getB() );
				}
			}
		}
	}

	private static void detachIds( final TLongCollection ids, final FragmentSegmentAssignment assignment )
	{
		for ( final TLongIterator id = ids.iterator(); id.hasNext(); )
		{
			assignment.detachFragment( id.next() );
		}
	}

}
