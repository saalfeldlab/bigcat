package bdv.bigcat.viewer.meshes;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.bigcat.ui.ARGBStream;
import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.atlas.source.AtlasSourceState;
import bdv.bigcat.viewer.meshes.MeshGenerator.ShapeKey;
import bdv.bigcat.viewer.state.FragmentSegmentAssignmentState;
import bdv.bigcat.viewer.state.SelectedSegments;
import bdv.bigcat.viewer.stream.AbstractHighlightingARGBStream;
import gnu.trove.set.hash.TLongHashSet;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Group;
import net.imglib2.Interval;
import net.imglib2.cache.Cache;
import net.imglib2.util.Pair;

/**
 *
 *
 * @author Philipp Hanslovsky
 */
public class MeshManager
{

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	private final DataSource< ?, ? > source;

	private final AtlasSourceState< ?, ? > state;

	private final List< MeshGenerator< DataSource< ?, ? > > > neurons = Collections.synchronizedList( new ArrayList<>() );

	private final Group root;

	private final SelectedSegments< ? > selectedSegments;

	private final ExecutorService es;

	public MeshManager(
			final DataSource< ?, ? > source,
			final AtlasSourceState< ?, ? > state,
			final Group root,
			final SelectedSegments< ? > selectedSegments,
			final ExecutorService es )
	{
		super();
		this.source = source;
		this.state = state;
		this.root = root;
		this.selectedSegments = selectedSegments;
		this.es = es;

		this.selectedSegments.addListener( this::update );

	}

	private void update()
	{
		synchronized ( neurons )
		{
			final TLongHashSet selectedSegments = new TLongHashSet( this.selectedSegments.getSelectedSegments() );
			final TLongHashSet currentlyShowing = new TLongHashSet();
			neurons.stream().mapToLong( MeshGenerator::getSegmentId ).forEach( currentlyShowing::add );
			final List< MeshGenerator< DataSource< ?, ? > > > toBeRemoved = neurons.stream().filter( n -> !selectedSegments.contains( n.getSegmentId() ) ).collect( Collectors.toList() );
			toBeRemoved.forEach( this::removeNeuron );
			neurons.removeAll( toBeRemoved );
			Arrays.stream( selectedSegments.toArray() ).filter( id -> !currentlyShowing.contains( id ) ).forEach( segment -> addNeuronAt( source, segment ) );
		}
	}

	private void addNeuronAt( final DataSource< ?, ? > source, final long segment )
	{

		final FragmentSegmentAssignmentState< ? > assignment = state.assignmentProperty().get();
		if ( assignment == null )
			return;
		final ARGBStream streams = state.streamProperty().get();

		if ( streams == null || !( streams instanceof AbstractHighlightingARGBStream ) )
			return;

		final AbstractHighlightingARGBStream stream = ( AbstractHighlightingARGBStream ) streams;
		final BooleanProperty colorLookupChanged = new SimpleBooleanProperty( false );
		stream.addListener( () -> colorLookupChanged.set( true ) );
		colorLookupChanged.addListener( ( obs, oldv, newv ) -> colorLookupChanged.set( false ) );

		final Cache< Long, Interval[] >[] blockListCache = state.blocklistCacheProperty().get();
		final Cache< ShapeKey, Pair< float[], float[] > >[] meshCache = state.meshesCacheProperty().get();
		if ( meshCache == null || blockListCache == null )
			return;

		for ( final MeshGenerator< DataSource< ?, ? > > neuron : neurons )
			if ( neuron.getSource() == source && neuron.getSegmentId() == segment )
				return;

		LOG.debug( "Adding mesh for segment {}.", segment );
		final MeshGenerator< DataSource< ?, ? > > nfx = new MeshGenerator<>(
				segment,
				source,
				assignment,
				blockListCache,
				meshCache,
				colorLookupChanged,
				stream::argb,
				es );
		nfx.rootProperty().set( this.root );

		neurons.add( nfx );

	}

	public void removeNeuron( final MeshGenerator< DataSource< ?, ? > > neuron )
	{
		neuron.rootProperty().set( null );
	}

}
