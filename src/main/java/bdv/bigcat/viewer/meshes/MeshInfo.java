package bdv.bigcat.viewer.meshes;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiConsumer;

import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.state.FragmentSegmentAssignment;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;

public class MeshInfo
{

	private final IntegerProperty scaleLevel = new SimpleIntegerProperty();

	private final IntegerProperty simplificationIterations = new SimpleIntegerProperty();

	private final long segmentId;

	private final FragmentSegmentAssignment assignment;

	private final MeshManager meshManager;

	private final int numScaleLevels;

	public MeshInfo( final long segmentId, final FragmentSegmentAssignment assignment, final MeshManager meshManager, final int numScaleLevels )
	{
		super();
		this.segmentId = segmentId;
		this.assignment = assignment;
		this.meshManager = meshManager;

		this.scaleLevel.set( meshManager.scaleLevelProperty().get() );

		this.simplificationIterations.set( meshManager.meshSimplificationIterationsProperty().get() );

		this.scaleLevel.addListener( new PropagateChanges<>( ( mesh, newv ) -> mesh.scaleIndexProperty().set( newv.intValue() ) ) );

		this.simplificationIterations.addListener( new PropagateChanges<>( ( mesh, newv ) -> mesh.meshSimplificationIterationsProperty().set( newv.intValue() ) ) );

		this.numScaleLevels = numScaleLevels;

	}

	public long segmentId()
	{
		return this.segmentId;
	}

	public IntegerProperty scaleLevelProperty()
	{
		return this.scaleLevel;
	}

	public IntegerProperty simplificationIterationsProperty()
	{
		return this.simplificationIterations;
	}

	public FragmentSegmentAssignment assignment()
	{
		return this.assignment;
	}

	public int numScaleLevels()
	{
		return this.numScaleLevels;
	}

	private class PropagateChanges< T > implements ChangeListener< T >
	{

		final BiConsumer< MeshGenerator< DataSource< ?, ? > >, T > apply;

		public PropagateChanges( final BiConsumer< MeshGenerator< DataSource< ?, ? > >, T > apply )
		{
			super();
			this.apply = apply;
		}

		@Override
		public void changed( final ObservableValue< ? extends T > observable, final T oldValue, final T newValue )
		{
			final long[] fragments = assignment.getFragments( segmentId ).toArray();
			final Map< Long, MeshGenerator< DataSource< ?, ? > > > meshes = meshManager.unmodifiableMeshMap();
			Arrays.stream( fragments ).mapToObj( meshes::get ).filter( m -> m != null ).forEach( n -> apply.accept( n, newValue ) );
		}

	}

	@Override
	public int hashCode()
	{
		return Long.hashCode( segmentId );
	}

	@Override
	public boolean equals( final Object o )
	{
		return o instanceof MeshInfo && ( ( MeshInfo ) o ).segmentId == segmentId;
	}

}
