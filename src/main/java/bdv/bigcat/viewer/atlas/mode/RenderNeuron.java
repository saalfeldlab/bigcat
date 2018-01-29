package bdv.bigcat.viewer.atlas.mode;

import java.lang.invoke.MethodHandles;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.bigcat.ui.ARGBStream;
import bdv.bigcat.viewer.ToIdConverter;
import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.atlas.source.AtlasSourceState;
import bdv.bigcat.viewer.atlas.source.SourceInfo;
import bdv.bigcat.viewer.bdvfx.ViewerPanelFX;
import bdv.bigcat.viewer.state.FragmentSegmentAssignmentState;
import bdv.bigcat.viewer.state.GlobalTransformManager;
import bdv.bigcat.viewer.state.SelectedIds;
import bdv.bigcat.viewer.viewer3d.NeuronsFX;
import bdv.labels.labelset.Label;
import bdv.viewer.Source;
import bdv.viewer.state.ViewerState;
import javafx.scene.Group;
import javafx.scene.input.MouseEvent;
import net.imglib2.RandomAccess;
import net.imglib2.converter.Converter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.integer.UnsignedLongType;

/**
 *
 *
 * @author Vanessa Leite
 * @author Philipp Hanslovsky
 */
public class RenderNeuron
{
	public static Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().getClass() );

	private final ViewerPanelFX viewer;

	private final boolean append;

	private final SourceInfo sourceInfo;

	private final GlobalTransformManager transformManager;

	private final Mode mode;

	private final NeuronsFX neurons;

	public RenderNeuron(
			final ViewerPanelFX viewer,
			final Group meshesGroup,
			final boolean append,
			final SourceInfo sourceInfo,
			final GlobalTransformManager transformManager,
			final Mode mode )
	{
		super();
		this.viewer = viewer;
		this.append = append;
		this.sourceInfo = sourceInfo;
		this.transformManager = transformManager;
		this.mode = mode;
		this.neurons = new NeuronsFX( sourceInfo, meshesGroup, mode );
	}

	public void click( final MouseEvent e )
	{
		final double x = e.getX();
		final double y = e.getY();
		synchronized ( viewer )
		{
			final ViewerState state = viewer.getState();
			final Source< ? > source = sourceInfo.currentSourceProperty().get();
			if ( source != null && sourceInfo.getState( source ).visibleProperty().get() )
				if ( source instanceof DataSource< ?, ? > )
				{
					final int sourceIndex = sourceInfo.trackVisibleSources().indexOf( source );
					final DataSource< ?, ? > dataSource = sourceInfo.getState( source ).dataSourceProperty().get();
					final Optional< Function< ?, Converter< ?, BoolType > > > toBoolConverter = sourceInfo.toBoolConverter( source );
					final Optional< ToIdConverter > idConverter = sourceInfo.toIdConverter( source );
					final Optional< SelectedIds > selectedIds = sourceInfo.selectedIds( source, mode );
					final Optional< ? extends FragmentSegmentAssignmentState< ? > > assignment = sourceInfo.assignment( source );
					final Optional< ARGBStream > stream = sourceInfo.stream( source, mode );
					if ( toBoolConverter.isPresent() && idConverter.isPresent() && selectedIds.isPresent() && assignment.isPresent() && stream.isPresent() )
					{
						final AffineTransform3D viewerTransform = new AffineTransform3D();
						state.getViewerTransform( viewerTransform );
						final int bestMipMapLevel = state.getBestMipMapLevel( viewerTransform, sourceIndex );

						final double[] worldCoordinate = new double[] { x, y, 0 };
						viewerTransform.applyInverse( worldCoordinate, worldCoordinate );

						final double[] imageCoordinate = new double[ worldCoordinate.length ];
						final AffineTransform3D transform = new AffineTransform3D();
						dataSource.getSourceTransform( 0, 0, transform );
						transform.applyInverse( imageCoordinate, worldCoordinate );
						final AtlasSourceState< ?, ? > sourceState = sourceInfo.getState( dataSource );
						final RandomAccess< UnsignedLongType > clickLocation = sourceState.getUnsignedLongSource( 0, 0 ).randomAccess();
						for ( int d = 0; d < imageCoordinate.length; ++d )
							clickLocation.setPosition( ( long ) imageCoordinate[ d ], d );

						final long selectedId = clickLocation.get().getIntegerLong();

						if ( Label.regular( selectedId ) )
						{
							System.out.println( "ABOUT TO RENDER!" );
							final long segmentId = assignment.get().getSegment( selectedId );
							final SelectedIds selIds = selectedIds.get();

							if ( selIds.isActive( selectedId ) )
								neurons.addNeuronAt( dataSource, clickLocation );
							else
								neurons.removeNeuron( dataSource, segmentId );
						}
						else
							LOG.warn( "Selected irregular label: {}. Will not render.", selectedId );
					}
				}
		}
	}

}
