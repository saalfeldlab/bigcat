package bdv.bigcat.viewer.viewer3d;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.LongToIntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.bigcat.viewer.state.FragmentSegmentAssignment;
import bdv.bigcat.viewer.util.InvokeOnJavaFXApplicationThread;
import gnu.trove.set.hash.TLongHashSet;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.cache.Cache;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.util.Pair;

/**
 *
 * @author Philipp Hanslovsky
 *
 * @param <T>
 */
public class NeuronFX< T >
{

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	public static class ShapeKey
	{
		private final long shapeId;

		private final int scaleIndex;

		private final int meshSimplificationIterations;

		private final long[] min;

		private final long[] max;

		public ShapeKey(
				final long shapeId,
				final int scaleIndex,
				final int meshSimplificationIterations,
				final long[] min,
				final long[] max )
		{
			this.shapeId = shapeId;
			this.scaleIndex = scaleIndex;
			this.meshSimplificationIterations = meshSimplificationIterations;
			this.min = min;
			this.max = max;
		}

		@Override
		public String toString()
		{
			return String.format( "{shapeId=%d, scaleIndex=%d, simplifications=%d, min=%s, max=%s}", shapeId, scaleIndex, meshSimplificationIterations, Arrays.toString( min ), Arrays.toString( max ) );
		}

		@Override
		public int hashCode()
		{
			int result = scaleIndex;
			result = 31 * result + ( int ) ( shapeId ^ shapeId >>> 32 );
			result = 31 * result + meshSimplificationIterations;
			result = 31 * result + Arrays.hashCode( this.min );
			result = 31 * result + Arrays.hashCode( this.max );
			return result;
		}

		@Override
		public boolean equals( final Object other )
		{
			if ( other instanceof ShapeKey )
			{
				final ShapeKey otherShapeKey = ( ShapeKey ) other;
				return shapeId == otherShapeKey.shapeId && otherShapeKey.scaleIndex == scaleIndex;
			}
			return false;
		}

		public long shapeId()
		{
			return this.shapeId;
		}

		public int scaleIndex()
		{
			return this.scaleIndex;
		}

		public int meshSimplificationIterations()
		{
			return this.meshSimplificationIterations;
		}

		public long[] min()
		{
			return min.clone();
		}

		public long[] max()
		{
			return max.clone();
		}

		public void min( final long[] min )
		{
			System.arraycopy( this.min, 0, min, 0, min.length );
		}

		public void max( final long[] max )
		{
			System.arraycopy( this.max, 0, max, 0, max.length );
		}

		public Interval interval()
		{
			return new FinalInterval( min, max );
		}

	}

	public static class BlockListKey
	{

		private final long id;

		private final int scaleIndex;

		public BlockListKey( final long id, final int scaleIndex )
		{
			super();
			this.id = id;
			this.scaleIndex = scaleIndex;
		}

		public long id()
		{
			return this.id;
		}

		public int scaleIndex()
		{
			return this.scaleIndex;
		}

		@Override
		public int hashCode()
		{
			int result = scaleIndex;
			result = 31 * result + ( int ) ( id ^ id >> 32 );
			return result;
		}

		@Override
		public boolean equals( final Object other )
		{
			if ( other instanceof BlockListKey )
			{
				final BlockListKey otherKey = ( BlockListKey ) other;
				return id == otherKey.id && scaleIndex == otherKey.scaleIndex;
			}
			return false;
		}

	}

	private final LongProperty segmentId;

	private final T source;

	private final FragmentSegmentAssignment assignment;

	private final Cache< BlockListKey, long[] > blockListCache;

	private final Cache< ShapeKey, Pair< float[], float[] > > meshCache;

	private final BooleanProperty isVisible = new SimpleBooleanProperty( true );

	private final ObservableMap< ShapeKey, MeshView > meshes = FXCollections.observableHashMap();

	private final IntegerProperty scaleIndex = new SimpleIntegerProperty( 0 );

	private final IntegerProperty meshSimplificationIteratoins = new SimpleIntegerProperty( 0 );

	private final BooleanProperty changed = new SimpleBooleanProperty( false );

	private final BooleanProperty colorLookupChanged = new SimpleBooleanProperty( false );

	private final ObjectProperty< Group > root = new SimpleObjectProperty<>();

	private final BooleanProperty isReady = new SimpleBooleanProperty( true );

	private final LongToIntFunction colorLookup;

	private final ExecutorService es;

	private final List< Future< ? > > activeTasks = new ArrayList<>();

	//
	public NeuronFX(
			final long segmentId,
			final T source,
			final FragmentSegmentAssignment assignment,
			final Cache< BlockListKey, long[] > blockListCache,
			final Cache< ShapeKey, Pair< float[], float[] > > meshCache,
			final ObservableBooleanValue colorLookupChanged,
			final LongToIntFunction colorLookup,
			final ExecutorService es )
	{
		super();
		this.segmentId = new SimpleLongProperty( segmentId );
		this.source = source;
		this.assignment = assignment;
		this.blockListCache = blockListCache;
		this.meshCache = meshCache;
		this.colorLookup = colorLookup;
		this.es = es;

		this.changed.addListener( ( obs, oldv, newv ) -> updateMeshes() );
		this.changed.addListener( ( obs, oldv, newv ) -> changed.set( false ) );
		this.segmentIdProperty().addListener( ( obs, oldv, newv ) -> changed.set( true ) );
		this.colorLookupChanged.bind( colorLookupChanged );
		final BooleanBinding scaleOrSimplificationChanged = Bindings.createBooleanBinding( () -> true, scaleIndex, meshSimplificationIteratoins );

		scaleOrSimplificationChanged.addListener( ( obs, oldv, newv ) -> changed.set( true ) );

		this.root.addListener( ( obs, oldv, newv ) -> {
			InvokeOnJavaFXApplicationThread.invoke( () -> {
				synchronized ( this.meshes )
				{
					Optional.ofNullable( oldv ).ifPresent( g -> this.meshes.forEach( ( id, mesh ) -> g.getChildren().remove( mesh ) ) );
					Optional.ofNullable( newv ).ifPresent( g -> this.meshes.forEach( ( id, mesh ) -> g.getChildren().add( mesh ) ) );
				}
			} );
		} );

		this.meshes.addListener( ( MapChangeListener< ShapeKey, MeshView > ) change -> {
			Optional.ofNullable( this.root.get() ).ifPresent( group -> {
				if ( change.wasRemoved() )
					InvokeOnJavaFXApplicationThread.invoke( () -> group.getChildren().remove( change.getValueRemoved() ) );
				else if ( change.wasAdded() )
					InvokeOnJavaFXApplicationThread.invoke( () -> group.getChildren().add( change.getValueAdded() ) );
			} );
		} );

		this.colorLookupChanged.addListener( ( obs, oldv, newv ) -> {
			if ( newv )
				for ( final Entry< ShapeKey, MeshView > mesh : meshes.entrySet() )
				{
					final Material material = mesh.getValue().getMaterial();
					if ( material instanceof PhongMaterial )
					{
						final PhongMaterial pm = ( PhongMaterial ) material;
						InvokeOnJavaFXApplicationThread.invoke( () -> pm.setDiffuseColor( fromInt( colorLookup.applyAsInt( mesh.getKey().shapeId() ) ) ) );
					}
				}
		} );
		this.changed.set( true );

	}

	private void updateMeshes()
	{
		synchronized ( meshes )
		{
			this.meshes.clear();
		}

		synchronized ( activeTasks )
		{
			this.activeTasks.forEach( f -> f.cancel( true ) );
			this.activeTasks.clear();
		}
		final TLongHashSet fragments = assignment.getFragments( segmentId.get() );
		fragments.forEach( id -> {
			final int scaleIndex = this.scaleIndex.get();
			try
			{
				final long[] blocks = blockListCache.get( new BlockListKey( id, scaleIndex ) );
				final List< ShapeKey > keys = new ArrayList<>();
				for ( int i = 0; i < blocks.length; i += 6 )
				{
					final long[] min = new long[] { blocks[ i + 0 ], blocks[ i + 1 ], blocks[ i + 2 ] };
					final long[] max = new long[] { blocks[ i + 3 ], blocks[ i + 4 ], blocks[ i + 5 ] };
					keys.add( new ShapeKey( id, scaleIndex, meshSimplificationIteratoins.get(), min, max ) );
				}
				final ArrayList< Future< Void > > tasks = new ArrayList<>();
				synchronized ( this.activeTasks )
				{
					for ( final ShapeKey key : keys )
						tasks.add( es.submit( () -> {
							try
							{
								final Pair< float[], float[] > verticesAndNormals = meshCache.get( key );
								final float[] vertices = verticesAndNormals.getA();
								final float[] normals = verticesAndNormals.getB();
								final TriangleMesh mesh = new TriangleMesh();
								mesh.getPoints().addAll( vertices );
								mesh.getNormals().addAll( normals );
								mesh.getTexCoords().addAll( 0, 0 );
								mesh.setVertexFormat( VertexFormat.POINT_NORMAL_TEXCOORD );
								final int[] faceIndices = new int[ vertices.length ];
								for ( int i = 0, k = 0; i < faceIndices.length; i += 3, ++k )
								{
									faceIndices[ i + 0 ] = k;
									faceIndices[ i + 1 ] = k;
									faceIndices[ i + 2 ] = 0;
								}
								mesh.getFaces().addAll( faceIndices );
								final PhongMaterial material = new PhongMaterial();
								material.setSpecularColor( new Color( 1, 1, 1, 1.0 ) );
								material.setSpecularPower( 50 );
								material.setDiffuseColor( fromInt( colorLookup.applyAsInt( id ) ) );
								final MeshView mv = new MeshView( mesh );
								mv.setOpacity( 1.0 );
								synchronized ( this.isVisible )
								{
									mv.visibleProperty().bind( this.isVisible );
								}
								mv.setCullFace( CullFace.NONE );
								mv.setMaterial( material );
								if ( !Thread.interrupted() )
									synchronized ( meshes )
									{
										meshes.put( key, mv );
									}
							}
							catch ( final ExecutionException e )
							{
								LOG.warn( "Was not able to retrieve mesh for {}: {}", key, e.getMessage() );
							}
							catch ( final RuntimeException e )
							{
								LOG.warn( "{} : {}", e.getClass(), e.getMessage() );
								e.printStackTrace();
								throw e;
							}
							return null;

						} ) );
					this.activeTasks.addAll( tasks );
				}
				for ( final Future< Void > future : tasks )
					try
					{
						future.get();
					}
					catch ( final Exception e )
					{
						LOG.warn( "{} in neuron mesh generation: {}", e.getClass(), e.getMessage() );
						e.printStackTrace();
					}
			}
			catch ( final ExecutionException e )
			{
				e.printStackTrace();
			}
			return true;
		} );
	}

	private static final Color fromInt( final int argb )
	{
		return Color.rgb( ARGBType.red( argb ), ARGBType.green( argb ), ARGBType.blue( argb ), 1.0 );
	}

	public T getSource()
	{
		return source;
	}

	public long getSegmentId()
	{
		return segmentId.get();
	}

	public LongProperty segmentIdProperty()
	{
		return this.segmentId;
	}

	public ObjectProperty< Group > rootProperty()
	{
		return this.root;
	}

	public void redraw()
	{
		this.changed.set( true );
	}

}
