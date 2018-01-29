package bdv.bigcat.viewer.viewer3d;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import bdv.bigcat.ui.ARGBStream;
import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.atlas.mode.Mode;
import bdv.bigcat.viewer.atlas.source.AtlasSourceState;
import bdv.bigcat.viewer.atlas.source.SourceInfo;
import bdv.bigcat.viewer.state.FragmentSegmentAssignmentState;
import bdv.bigcat.viewer.stream.AbstractHighlightingARGBStream;
import bdv.bigcat.viewer.viewer3d.NeuronFX.BlockListKey;
import bdv.bigcat.viewer.viewer3d.NeuronFX.ShapeKey;
import bdv.labels.labelset.Label;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

/**
 *
 *
 * @author Vanessa Leite
 * @author Philipp Hanslovsky
 * @param <T>
 * @param <F>
 */
public class NeuronsFX
{

	private final SourceInfo sourceInfo;

	private final ObservableList< Pair< Localizable, NeuronFX< DataSource< ?, ? > > > > neurons = FXCollections.observableArrayList();

	private final Group root;

	private final Mode mode;

	private final ExecutorService es;

	public NeuronsFX(
			final SourceInfo sourceInfo,
			final Group root,
			final Mode mode,
			final ExecutorService es )
	{
		super();
		this.sourceInfo = sourceInfo;
		this.root = root;
		this.mode = mode;
		this.es = es;
	}

	public void addNeuronAt( final DataSource< ?, ? > source, final Localizable clickLocation )
	{
		final AtlasSourceState< ?, ? > state = sourceInfo.getState( source );
		if ( state == null )
			return;

		final FragmentSegmentAssignmentState< ? > assignmentForSource = state.assignmentProperty().get();
		if ( assignmentForSource == null )
			return;

		final ARGBStream streams = state.streamProperty().get();
		if ( streams == null || !( streams instanceof AbstractHighlightingARGBStream ) )
			return;
		final AbstractHighlightingARGBStream stream = ( AbstractHighlightingARGBStream ) streams;
		final BooleanProperty colorLookupChanged = new SimpleBooleanProperty( false );
		stream.addListener( () -> colorLookupChanged.set( true ) );
		colorLookupChanged.addListener( ( obs, oldv, newv ) -> colorLookupChanged.set( false ) );

		final RandomAccessibleInterval< UnsignedLongType > unsignedLongSource = state.getUnsignedLongSource( 0, 0 );
		if ( unsignedLongSource == null )
			return;

		final RandomAccess< UnsignedLongType > access = unsignedLongSource.randomAccess();
		access.setPosition( clickLocation );
		final long fragmentId = access.get().get();

		final Cache< BlockListKey, long[] > blockListCache = state.blocklistCacheProperty().get();
		final Cache< ShapeKey, Pair< float[], float[] > > meshCache = state.meshesCacheProperty().get();
		if ( meshCache == null || blockListCache == null )
			return;

		if ( Label.regular( fragmentId ) && fragmentId != Label.BACKGROUND )
		{
			final long segmentId = assignmentForSource.getSegment( fragmentId );

			for ( final Pair< Localizable, NeuronFX< DataSource< ?, ? > > > neuron : neurons )
				if ( neuron.getB().getSource() == source && neuron.getB().getSegmentId() == segmentId )
					return;

			final NeuronFX< DataSource< ?, ? > > nfx = new NeuronFX<>(
					segmentId,
					source,
					mode,
					assignmentForSource,
					blockListCache,
					meshCache,
					colorLookupChanged,
					stream::argb,
					es );
			nfx.rootProperty().set( this.root );

			neurons.add( new ValuePair<>( clickLocation, nfx ) );

			assignmentForSource.addListener( () -> nfx.segmentIdProperty().set( assignmentForSource.getSegment( fragmentId ) ) );

		}
	}

	public void removeNeuron( final DataSource< ?, ? > source, final long segmentId )
	{
		synchronized ( neurons )
		{
			final List< Pair< Localizable, NeuronFX< DataSource< ?, ? > > > > removal = neurons
					.stream()
					.filter( n -> n.getB().getSegmentId() == segmentId )
					.collect( Collectors.toList() );
			neurons.removeAll( removal );
			removal.forEach( n -> n.getB().rootProperty().set( null ) );
		}
	}

}
