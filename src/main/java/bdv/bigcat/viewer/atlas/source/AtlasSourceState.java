package bdv.bigcat.viewer.atlas.source;

import java.util.function.Function;

import bdv.bigcat.composite.Composite;
import bdv.bigcat.ui.ARGBStream;
import bdv.bigcat.viewer.ToIdConverter;
import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.atlas.data.mask.MaskedSource;
import bdv.bigcat.viewer.state.FragmentSegmentAssignmentState;
import bdv.bigcat.viewer.state.SelectedIds;
import bdv.bigcat.viewer.viewer3d.NeuronFX.BlockListKey;
import bdv.bigcat.viewer.viewer3d.NeuronFX.ShapeKey;
import bdv.util.IdService;
import bdv.viewer.SourceAndConverter;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.paint.Color;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.type.Type;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.Pair;

public class AtlasSourceState< T extends Type< T >, D extends Type< D > >
{

	public enum TYPE
	{
		RAW, LABEL
	};

	public AtlasSourceState(
			final DataSource< D, T > dataSource,
			final Converter< T, ARGBType > converter,
			final Composite< ARGBType, ARGBType > composite,
			final TYPE type )
	{
		this.dataSource.set( dataSource );
		this.converter.set( converter );
		this.composite.set( composite );
		this.visible.set( true );
		this.type.set( type );
		this.name.set( dataSource.getName() );
	}

	private final ObjectProperty< DataSource< D, T > > dataSource = new SimpleObjectProperty<>();

	private final ObjectProperty< Converter< T, ARGBType > > converter = new SimpleObjectProperty<>();

	private final ObjectProperty< Composite< ARGBType, ARGBType > > composite = new SimpleObjectProperty<>();

	private final BooleanProperty visible = new SimpleBooleanProperty();

	private final ObjectProperty< TYPE > type = new SimpleObjectProperty<>();

	private final ObjectProperty< Function< D, Converter< D, BoolType > > > maskGenerator = new SimpleObjectProperty<>();

	private final ObjectProperty< MaskedSource< ?, ? > > maskedSource = new SimpleObjectProperty<>();

	private final ObjectProperty< FragmentSegmentAssignmentState< ? > > assignment = new SimpleObjectProperty<>();

	private final ObjectProperty< ToIdConverter > toIdConverter = new SimpleObjectProperty<>();

	private final ObjectProperty< ARGBStream > stream = new SimpleObjectProperty();

	private final ObjectProperty< SelectedIds > selectedIds = new SimpleObjectProperty<>();

	private final ObjectProperty< IdService > idService = new SimpleObjectProperty<>();

	private final DoubleProperty selectionMin = new SimpleDoubleProperty( Double.NaN );

	private final DoubleProperty selectionMax = new SimpleDoubleProperty( Double.NaN );

	private final StringProperty name = new SimpleStringProperty();

	private final ObjectProperty< Cache< BlockListKey, long[] > > blockListCache = new SimpleObjectProperty<>( null );

	private final ObjectProperty< Cache< ShapeKey, Pair< float[], float[] > > > meshesCache = new SimpleObjectProperty<>( null );

	public SourceAndConverter< T > getSourceAndConverter()
	{
		return new SourceAndConverter<>( dataSource.get(), converter.get() );
	}

	public ReadOnlyObjectProperty< Converter< T, ARGBType > > converterProperty()
	{
		return this.converter;
	}

	public ObjectProperty< Composite< ARGBType, ARGBType > > compositeProperty()
	{
		return this.composite;
	}

	public BooleanProperty visibleProperty()
	{
		return this.visible;
	}

	public ReadOnlyObjectProperty< DataSource< D, T > > dataSourceProperty()
	{
		return this.dataSource;
	}

	public ObjectProperty< Function< D, Converter< D, BoolType > > > maskGeneratorProperty()
	{
		return this.maskGenerator;
	}

	public ObjectProperty< TYPE > typeProperty()
	{
		return this.type;
	}

	public ObjectProperty< MaskedSource< ?, ? > > maskedSourceProperty()
	{
		return this.maskedSource;
	}

	public RandomAccessibleInterval< UnsignedLongType > getUnsignedLongSource( final int t, final int level )
	{
		final ToIdConverter toIdConverter = toIdConverterProperty().get();

		if ( toIdConverter == null )
			return null;

		final DataSource< D, T > dataSource = dataSourceProperty().get();

		if ( dataSource == null )
			return null;

		return Converters.convert(
				dataSource.getDataSource( t, level ),
				( source, target ) -> target.set( toIdConverter.biggestFragment( source ) ),
				new UnsignedLongType() );

	}

//	public static class LabelSourceState< T extends Type< T >, D extends Type< D > > extends AtlasSourceState< T, D >
//	{
//
//		public LabelSourceState( final DataSource< D, T > dataSource, final Converter< T, ARGBType > converter )
//		{
//			super.dataSource.set( dataSource );
//			typeProperty().set( TYPE.RAW );
//			setConverter( converter );
//		}

	public ObjectProperty< FragmentSegmentAssignmentState< ? > > assignmentProperty()
	{
		return this.assignment;
	}

	public ObjectProperty< ToIdConverter > toIdConverterProperty()
	{
		return this.toIdConverter;
	}

	public ObjectProperty< ARGBStream > streamProperty()
	{
		return this.stream;
	}

	public ObjectProperty< SelectedIds > selectedIdsProperty()
	{
		return this.selectedIds;
	}

	public ObjectProperty< IdService > idServiceProperty()
	{
		return this.idService;
	}
//	}

//	public static class RawSourceState< T extends RealType< T >, D extends Type< D > > extends AtlasSourceState< T, D >
//	{

//		public RawSourceState( final DataSource< D, T > dataSource, final double min, final double max )
//		{
//			final RealARGBColorConverter< T > conv = new InvertingARGBColorConverter<>( min, max );
//			this.selectionMin.addListener( ( obs, oldv, newv ) -> this.min.set( this.min.get() < newv.doubleValue() ? newv.doubleValue() : this.min.get() ) );
//			this.selectionMax.addListener( ( obs, oldv, newv ) -> this.max.set( this.max.get() > newv.doubleValue() ? newv.doubleValue() : this.max.get() ) );
//			this.selectionMin.set( min );
//			this.selectionMax.set( max );
//			this.minProperty().addListener( ( obs, oldv, newv ) -> conv.setMin( newv.doubleValue() ) );
//			this.maxProperty().addListener( ( obs, oldv, newv ) -> conv.setMax( newv.doubleValue() ) );
//			this.minProperty().set( min );
//			this.maxProperty().set( max );
//			this.colorProperty().addListener( ( obs, oldv, newv ) -> conv.setColor( toARGBType( newv ) ) );
//			setConverter( conv );
//			super.dataSource.set( dataSource );
//			typeProperty().set( TYPE.RAW );
//		}

	public DoubleProperty selectionMinProperty()
	{
		return this.selectionMin;
	}

	public DoubleProperty selectionMaxProperty()
	{
		return this.selectionMax;
	}

	public ObjectProperty< Cache< BlockListKey, long[] > > blocklistCacheProperty()
	{
		return this.blockListCache;
	}

	public StringProperty nameProperty()
	{
		return this.name;
	}

	public ObjectProperty< Cache< ShapeKey, Pair< float[], float[] > > > meshesCacheProperty()
	{
		return this.meshesCache;
	}

	private static ARGBType toARGBType( final Color color )
	{
		return new ARGBType(
				( int ) ( color.getOpacity() * 255 + 0.5 ) << 24 |
						( int ) ( color.getRed() * 255 + 0.5 ) << 16 |
						( int ) ( color.getGreen() * 255 + 0.5 ) << 8 |
						( int ) ( color.getBlue() * 255 + 0.5 ) << 0 );
	}

}
