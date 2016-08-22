package bdv.bigcat.control;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;

import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.ClickBehaviour;
import org.scijava.ui.behaviour.InputTriggerAdder;
import org.scijava.ui.behaviour.InputTriggerMap;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import bdv.bigcat.control.GrowingStoreRandomAccessible.Factory;
import bdv.labels.labelset.Label;
import bdv.labels.labelset.LabelMultisetType;
import bdv.util.sparse.MapSparseRandomAccessible;
import bdv.util.sparse.MapSparseRandomAccessible.HashableLongArray;
import bdv.viewer.ViewerPanel;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccess;
import net.imglib2.algorithm.fill.Filter;
import net.imglib2.algorithm.fill.FloodFill;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Pair;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 * Persist fragment segment assignments, painted labels, viewer state, and
 * flattened labels.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SendPaintedLabelsToSolver
{
	final private ViewerPanel viewer;

	final private RandomAccessibleInterval< LabelMultisetType > labelMultisetSource;

	final private RandomAccessibleInterval< LongType > labelSource;

	final private AffineTransform3D labelTransform;

	// for behavioUrs
	private final BehaviourMap behaviourMap = new BehaviourMap();

	private final InputTriggerMap inputTriggerMap = new InputTriggerMap();

	private final InputTriggerAdder inputAdder;

	public BehaviourMap getBehaviourMap()
	{
		return behaviourMap;
	}

	public InputTriggerMap getInputTriggerMap()
	{
		return inputTriggerMap;
	}

	private final int[] cellDimensions;

	private final Socket socket;

	public SendPaintedLabelsToSolver(
			final ViewerPanel viewer,
			final RandomAccessibleInterval< LabelMultisetType > labelMultisetSource,
			final RandomAccessibleInterval< LongType > labelSource,
			final AffineTransform3D labelTransform,
			final int[] cellDimensions,
			final InputTriggerConfig config,
			final Socket socket )
	{
		this.viewer = viewer;
		this.labelMultisetSource = labelMultisetSource;
		this.labelSource = labelSource;
		this.labelTransform = labelTransform;
		this.cellDimensions = cellDimensions;
		this.inputAdder = config.inputTriggerAdder( inputTriggerMap, "Solver" );
		this.socket = socket;

		new SendLabels( "send painted label to solver", "ctrl shift A button1" ).register();
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
//				paintAccess.setPosition( x, 0 );
//				paintAccess.setPosition( y, 1 );
//				paintAccess.setPosition( 0, 2 );
//				viewer.displayToGlobalCoordinates( paintAccess );
				setCoordinates( paintAccess, x, y );

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

					final Factory< BitType > factory = ( min, max, u ) -> {
						final long[] dim = new long[ min.length ];
						long product = max[ 0 ] - min[ 0 ] + 1;
						dim[ 0 ] = product;
						for ( int d = 1; d < max.length; ++d )
						{
							final long currentDim = max[ d ] - min[ d ] + 1;
							product *= currentDim;
							dim[ d ] = currentDim;
						}

						final Img< BitType > result;
						if ( product > Integer.MAX_VALUE )
						{
							result = new CellImgFactory< BitType >( cellDimensions ).create( dim, u );
						}
						else
						{
							result = ArrayImgs.bits( dim );
						}

//						for ( int i = 0; i < 3; ++i )
//						{
//							System.out.print( Views.translate( result, min ).min( i ) + ", " );
//						}
//						System.out.println();
						return Views.translate( result, min );
					};

//					System.out.println( Arrays.toString( initialMin ) + " " + Arrays.toString( initialMax ) );
//					final GrowingStoreRandomAccessible< BitType > target =
//							new GrowingStoreRandomAccessible<>( initialMin, initialMax, factory, new BitType() );

					final long[] cellDimensionsLong = new long[] { cellDimensions[ 0 ], cellDimensions[ 1 ], cellDimensions[ 2 ] };
					final HashMap< HashableLongArray, RandomAccessibleInterval< BitType > > hm = new HashMap<>();

					final MapSparseRandomAccessible< BitType > target =
							new MapSparseRandomAccessible<>( new ArrayImgFactory<>(), cellDimensionsLong, new BitType( false ), hm );
					final ExtendedRandomAccessibleInterval< LongType, RandomAccessibleInterval< LongType > > source =
							Views.extendValue( labelSource, new LongType( Label.OUTSIDE ) );
					final BitType fillLabel = new BitType( true );
					final DiamondShape shape = new DiamondShape( 1 );
					final Filter< Pair< LongType, BitType >, Pair< LongType, BitType > > filter = ( lb1, lb2 ) -> {
						return !lb1.getB().get() && lb1.getA().valueEquals( lb2.getA() );
					};
					// !(b.get() || l.get() != labelLong)?
					System.out.println( "Start fill" );
					FloodFill.fill( source, target, seed, fillLabel, shape, filter );
					System.out.println( "Stop fill" );
//					final BlockedInterval< BitType > blocked = BlockedInterval.createValueExtended(
//							Views.interval( target, target.getIntervalOfSizeOfStore() ),
//							new long[] { cellDimensions[ 0 ], cellDimensions[ 1 ], cellDimensions[ 2 ] },
//							new BitType( false ) );

					final long[] min = new long[] { Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE };
					final long[] max = new long[] { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };

					for ( final HashableLongArray h : hm.keySet() )
					{
						final long[] data = h.getData();
						for ( int d = 0; d < min.length; ++d )
						{
							min[ d ] = Math.min( min[ d ], data[ d ] );
							max[ d ] = Math.max( max[ d ], data[ d ] );
						}
					}

					for ( int d = 0; d < 3; ++d )
					{
						min[ d ] *= cellDimensionsLong[ d ];
						max[ d ] = max[ d ] * cellDimensionsLong[ d ] + cellDimensionsLong[ d ] - 1;
					}

//					target.getIntervalOfSizeOfStore().min( min );
//					target.getIntervalOfSizeOfStore().max( max );
					System.out.println( "min: " + Arrays.toString( min ) );
					System.out.println( "max: " + Arrays.toString( max ) );

//					for ( final Entry< HashableLongArray, RandomAccessibleInterval< BitType > > keyRai : hm.entrySet() )
//					{
//						System.out.println( "keyra! " + Arrays.toString( keyRai.getKey().getData() ) + " " + Arrays.toString( cellDimensionsLong ) );
//						ImageJFunctions.show( keyRai.getValue(), Arrays.toString( keyRai.getKey().getData() ) );
//					}
//					ImageJFunctions.show( Views.interval( target, new FinalInterval( min, max ) ) );

//					BdvFunctions.show( new ConvertedRandomAccessibleInterval<>( Views.interval( target, new FinalInterval( min, max ) ), ( b1, b2 ) -> {
//						b2.set( !b1.get() );
//					}, new BitType() ), "BLA" );

//					blocked.min( min );
//					blocked.max( max );
//					System.out.println( Arrays.toString( min ) );
//					System.out.println( Arrays.toString( max ) );

//					final IntervalView< BitType > store = Views.interval( target, target.getIntervalOfSizeOfStore() );
//
//					for ( final BitType s : store )
//					{
//						if ( s.get() )
//						{
//							System.out.println( "TRUE VERDAMMT NOCHMAL!" );
//							break;
//						}
//					}
//					System.out.println( "War's true?" );
//
//					System.out.println( "Sending start!" );
//					for ( int d = 0; d < 3; ++d )
//					{
//						System.out.println( target.getIntervalOfSizeOfStore().min( d ) + " " + target.getIntervalOfSizeOfStore().max( d ) );
//					}
//					System.out.println( "INTV" );
					socket.send( "start", ZMQ.SNDMORE );
					final byte[] bboxArray = new byte[ 3 * Long.BYTES + 3 * Long.BYTES ];
					final ByteBuffer bboxBuffer = ByteBuffer.wrap( bboxArray );
					for ( final long m : min )
					{
						bboxBuffer.putLong( m );
					}
					for ( final long m : max )
					{
						bboxBuffer.putLong( m );
					}
					socket.send( bboxArray, ZMQ.SNDMORE );

//					final int size = [ cellDimensions[0] * ];
					final int cellSize = cellDimensions[0] * cellDimensions[1] * cellDimensions[2];

					final byte[] bytes = new byte[ cellSize + 3 * Long.BYTES + + 3 * Long.BYTES + 1 * Long.BYTES ]; // mask (cell) + min + max + label

					final ByteBuffer buffer = ByteBuffer.wrap( bytes );

					final byte ONE = ( byte ) 1;
					final byte ZERO = ( byte ) 0;

					final RandomAccess< BitType > maskAccess = target.randomAccess();

					final long[] currentMin = new long[ 3 ];
					final long[] currentMax = new long[ 3 ];

					for ( final Entry< HashableLongArray, RandomAccessibleInterval< BitType > > entry : hm.entrySet() )
					{
						boolean sendMessage = false;
						buffer.rewind();

						final RandomAccessibleInterval< BitType > rai = entry.getValue();
						rai.min( currentMin );
						rai.max( currentMax );

						for ( final long c : currentMin )
						{
							buffer.putLong( c );
						}

						for ( final long c : currentMax )
						{
							buffer.putLong( c );
						}
						buffer.putLong( labelLong );
						final IntervalView< BitType > cell = Views.interval(
								target,
								currentMin,
								currentMax );
						for ( final BitType bit : cell )
						{
//							System.out.println( bit + Arrays.toString( new long[] { xIndex, yIndex, zIndex } ) + " " + Arrays.toString( new long[] { xMax, yMax, zMax } ) );
							if ( bit.get() )
							{
								buffer.put( ONE );
								sendMessage = true;
//								System.out.println( "TRUE!" );
							}
							else
							{
								buffer.put( ZERO );
							}
						}
						if ( sendMessage )
						{
							System.out.println( "Sending message!" );
							socket.send( bytes, ZMQ.SNDMORE );
						}
//						System.out.println( sendMessage );
					}

//					for ( long zIndex = min[ 2 ]; zIndex < max[ 2 ]; zIndex += cellDimensions[ 2 ] )
//					{
//						for ( long yIndex = min[ 1 ]; yIndex < max[ 1 ]; yIndex += cellDimensions[ 1 ] )
//						{
//							for ( long xIndex = min[ 0 ]; xIndex < max[ 0 ]; xIndex += cellDimensions[ 0 ] )
//							{
//								boolean sendMessage = false;
//								buffer.rewind();
//
//								final long xMax = Math.min( xIndex + cellDimensions[ 0 ] - 1, max[ 0 ] );
//								final long yMax = Math.min( yIndex + cellDimensions[ 1 ] - 1, max[ 1 ] );
//								final long zMax = Math.min( zIndex + cellDimensions[ 2 ] - 1, max[ 2 ] );
//
//								buffer.putLong( xIndex );
//								buffer.putLong( yIndex );
//								buffer.putLong( zIndex );
//
//								buffer.putLong( xMax );
//								buffer.putLong( yMax );
//								buffer.putLong( zMax );
//
//								buffer.putLong( labelLong );
//
////								final IntervalView< BitType > cell = Views.offsetInterval(
////										Views.extendValue( store, new BitType( false ) ),
////										new long[] { xIndex, yIndex, zIndex },
////										new long[] { xMax, yMax, zMax } );
//								final IntervalView< BitType > cell = Views.interval(
//										target,
//										new long[] { xIndex, yIndex, zIndex },
//										new long[] { xMax, yMax, zMax } );
//								for ( final BitType bit : cell )
//								{
////									System.out.println( bit + Arrays.toString( new long[] { xIndex, yIndex, zIndex } ) + " " + Arrays.toString( new long[] { xMax, yMax, zMax } ) );
//									if ( bit.get() )
//									{
//										buffer.put( ONE );
//										sendMessage = true;
//										System.out.println( "TRUE!" );
//									}
//									else
//									{
//										buffer.put( ZERO );
//									}
//								}
//								if ( sendMessage )
//								{
//									System.out.println( "Sending message!" );
//									socket.send( bytes, ZMQ.SNDMORE );
//								}
//								System.out.println( sendMessage );
//							}
//						}
//					}
					socket.send( "stop" );

					// socket.send( ( byte[] ) null, ZMQ.SNDMORE );
				}
			}
		}
	}


}
