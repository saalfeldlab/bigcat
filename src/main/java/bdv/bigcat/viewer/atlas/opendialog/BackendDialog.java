package bdv.bigcat.viewer.atlas.opendialog;

import java.io.IOException;
import java.util.Optional;

import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.atlas.data.LabelDataSource;
import bdv.bigcat.viewer.atlas.opendialog.OpenSourceDialog.TYPE;
import bdv.util.volatiles.SharedQueue;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.effect.Effect;
import javafx.scene.effect.InnerShadow;
import javafx.scene.paint.Color;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;

public interface BackendDialog
{

	static final String RESOLUTION_KEY = "resolution";

	static final String OFFSET_KEY = "offset";

	static final String MIN_KEY = "min";

	static final String MAX_KEY = "max";

	final Effect textFieldNoErrorEffect = new TextField().getEffect();

	final Effect textFieldErrorEffect = new InnerShadow( 10, Color.ORANGE );

	public Node getDialogNode();

	public ObjectProperty< String > errorMessage();

	public default < T extends RealType< T > & NativeType< T >, V extends RealType< V > > Optional< DataSource< T, V > > getRaw(
			final String name,
			final double[] resolution,
			final double[] offset,
			final SharedQueue sharedQueue,
			final int priority ) throws IOException
	{
		return Optional.empty();
	}

	public default Optional< LabelDataSource< ?, ? > > getLabels(
			final String name,
			final double[] resolution,
			final double[] offset,
			final SharedQueue sharedQueue,
			final int priority ) throws IOException
	{
		return Optional.empty();
	}

	public default DoubleProperty resolutionX()
	{
		return new SimpleDoubleProperty( Double.NaN );
	}

	public default DoubleProperty resolutionY()
	{
		return new SimpleDoubleProperty( Double.NaN );
	}

	public default DoubleProperty resolutionZ()
	{
		return new SimpleDoubleProperty( Double.NaN );
	}

	public default DoubleProperty offsetX()
	{
		return new SimpleDoubleProperty( Double.NaN );
	}

	public default DoubleProperty offsetY()
	{
		return new SimpleDoubleProperty( Double.NaN );
	}

	public default DoubleProperty offsetZ()
	{
		return new SimpleDoubleProperty( Double.NaN );
	}

	public default DoubleProperty min()
	{
		return new SimpleDoubleProperty( Double.NaN );
	}

	public default DoubleProperty max()
	{
		return new SimpleDoubleProperty( Double.NaN );
	}

	public default void typeChanged( final TYPE type )
	{}

//	TODO
//	public DataSource< ?, ? > getChannels();

}
