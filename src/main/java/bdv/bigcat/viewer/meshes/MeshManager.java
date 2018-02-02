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
import bdv.bigcat.viewer.state.FragmentsInSelectedSegments;
import bdv.bigcat.viewer.stream.AbstractHighlightingARGBStream;
import gnu.trove.set.hash.TLongHashSet;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ObservableIntegerValue;
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

	private final FragmentsInSelectedSegments< ? > fragmentsInSelectedSegments;

	private final IntegerProperty meshSimplificationIterations = new SimpleIntegerProperty();

	private final ExecutorService es;

	public MeshManager(
			final DataSource< ?, ? > source,
			final AtlasSourceState< ?, ? > state,
			final Group root,
			final FragmentsInSelectedSegments< ? > fragmentsInSelectedSegments,
			final ObservableIntegerValue meshSimplificationIterations,
			final ExecutorService es )
	{
		super();
		this.source = source;
		this.state = state;
		this.root = root;
		this.fragmentsInSelectedSegments = fragmentsInSelectedSegments;
		this.meshSimplificationIterations.set( Math.max( meshSimplificationIterations.get(), 0 ) );
		meshSimplificationIterations.addListener( ( obs, oldv, newv ) -> {
			System.out.println( "ADDED MESH SIMPLIFICATION ITERATIONS" );
			this.meshSimplificationIterations.set( Math.max( newv.intValue(), 0 ) );
		} );

		this.es = es;

		this.fragmentsInSelectedSegments.addListener( this::update );

	}

	private void update()
	{
		synchronized ( neurons )
		{
			final TLongHashSet fragmentsInSelectedSegments = new TLongHashSet( this.fragmentsInSelectedSegments.getFragments() );
			final TLongHashSet currentlyShowing = new TLongHashSet();
			neurons.stream().mapToLong( MeshGenerator::getId ).forEach( currentlyShowing::add );
			final List< MeshGenerator< DataSource< ?, ? > > > toBeRemoved = neurons.stream().filter( n -> !fragmentsInSelectedSegments.contains( n.getId() ) ).collect( Collectors.toList() );
			toBeRemoved.forEach( this::removeNeuron );
			neurons.removeAll( toBeRemoved );
			Arrays.stream( fragmentsInSelectedSegments.toArray() ).filter( id -> !currentlyShowing.contains( id ) ).forEach( segment -> generateMesh( source, segment ) );
		}
	}

	private void generateMesh( final DataSource< ?, ? > source, final long id )
	{

		final FragmentSegmentAssignmentState< ? > assignment = state.assignmentProperty().get();
		if ( assignment == null )
			return;
		final ARGBStream streams = state.streamProperty().get();

		if ( streams == null || !( streams instanceof AbstractHighlightingARGBStream ) )
			return;

		final AbstractHighlightingARGBStream stream = ( AbstractHighlightingARGBStream ) streams;
		final IntegerProperty color = new SimpleIntegerProperty( stream.argb( id ) );
		stream.addListener( () -> color.set( stream.argb( id ) ) );
		assignment.addListener( () -> color.set( stream.argb( id ) ) );

		final Cache< Long, Interval[] >[] blockListCache = state.blocklistCacheProperty().get();
		final Cache< ShapeKey, Pair< float[], float[] > >[] meshCache = state.meshesCacheProperty().get();
		if ( meshCache == null || blockListCache == null )
			return;

		for ( final MeshGenerator< DataSource< ?, ? > > neuron : neurons )
			if ( neuron.getSource() == source && neuron.getId() == id )
				return;

		LOG.debug( "Adding mesh for segment {}.", id );
		final MeshGenerator< DataSource< ?, ? > > nfx = new MeshGenerator<>(
				id,
				source,
				blockListCache,
				meshCache,
				color,
				meshSimplificationIterations.get(),
				es );
		nfx.meshSimplificationIterationsProperty().bind( meshSimplificationIterations );
		nfx.meshSimplificationIterationsProperty().addListener( ( obs, oldv, newv ) -> System.out.println( "SETTING SIMPL ITER TO " + newv ) );
		nfx.rootProperty().set( this.root );

		neurons.add( nfx );

	}

	public void removeNeuron( final MeshGenerator< DataSource< ?, ? > > mesh )
	{
		mesh.rootProperty().set( null );
	}

}
