package bdv.bigcat.viewer;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.javafx.application.PlatformImpl;

import bdv.AbstractViewerSetupImgLoader;
import bdv.bigcat.viewer.atlas.Atlas;
import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.atlas.data.HDF5LabelMultisetDataSource;
import bdv.bigcat.viewer.atlas.data.RandomAccessibleIntervalDataSource;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.display.AbstractLinearRange;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.volatiles.VolatileARGBType;
import net.imglib2.type.volatiles.VolatileUnsignedByteType;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;

public class ExampleApplication2
{
	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	public static void main( final String[] args ) throws Exception
	{
		// Set the log level
		final String rawFile = "data/sample_B_20160708_frags_46_50.hdf";
		PlatformImpl.startup( () -> {} );
		final String rawDataset = "volumes/raw";
		final String labelsFile = rawFile;
		final String labelsDataset = "/volumes/labels/neuron_ids";

		final double[] resolution = { 4, 4, 40 };
		final double[] offset = { 424, 424, 560 };
		final int[] cellSize = { 145, 53, 5 };

		final int numPriorities = 20;
		final SharedQueue sharedQueue = new SharedQueue( 12, numPriorities );
		final VolatileGlobalCellCache cellCache = new VolatileGlobalCellCache( 1, 12 );

		final RandomAccessibleIntervalDataSource< UnsignedByteType, VolatileUnsignedByteType > rawSource =
				DataSource.createH5RawSource( "raw", rawFile, rawDataset, cellSize, resolution, sharedQueue, numPriorities - 1 );

		final Atlas viewer = new Atlas( sharedQueue );

//		final Viewer3DController controller = new Viewer3DController();
//		controller.setMode( Viewer3DController.ViewerMode.ONLY_ONE_NEURON_VISIBLE );

		final CountDownLatch latch = new CountDownLatch( 1 );
		Platform.runLater( () -> {
			final Stage stage = new Stage();
			try
			{
				viewer.start( stage );
			}
			catch ( final InterruptedException e )
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			stage.show();
//			final Viewer3D v3d = new Viewer3D( "appname", 100, 100, false );
//			new Thread( () -> v3d.main() ).start();
//			viewer.baseView().setInfoNode( v3d.getPanel() );
//			controller.setViewer3D( v3d );
//			controller.setResolution( resolution );
			latch.countDown();
		} );
		latch.await();

//		AffineTransform3D transform = new AffineTransform3D();
//		final long label = 7;
//		controller.renderAtSelectionMultiset( volumeLabels, transform, location, label );
//		Viewer3DController.generateMesh( volumeLabels, location );

		viewer.addRawSource( rawSource, 0., 255. );

		final HDF5LabelMultisetDataSource labelSpec2 = new HDF5LabelMultisetDataSource( labelsFile, labelsDataset, cellSize, "labels", cellCache, 1 );
		viewer.addLabelSource( labelSpec2 );

		final boolean demonstrateRemove = false;
		if ( demonstrateRemove )
		{
			final HDF5LabelMultisetDataSource labelSpec3 = new HDF5LabelMultisetDataSource( labelsFile, labelsDataset, cellSize, "labels2", cellCache, 2 );
			viewer.addLabelSource( labelSpec3 );

			Platform.runLater( () -> {
				final Dialog< Boolean > d = new Dialog<>();
				final ButtonType removeType = new ButtonType( "Remove extra source", ButtonData.OK_DONE );
				d.getDialogPane().getButtonTypes().add( removeType );
				final Button b = ( Button ) d.getDialogPane().lookupButton( removeType );
				d.show();
				d.setOnHiding( event -> {
					LOG.info( "Removing source!" );
					viewer.baseView().requestFocus();
					viewer.removeSource( labelSpec3 );
				} );
				viewer.baseView().requestFocus();
			} );
		}

	}

	public static class VolatileRealARGBConverter< T extends RealType< T > > extends AbstractLinearRange implements Converter< Volatile< T >, VolatileARGBType >
	{

		public VolatileRealARGBConverter( final double min, final double max )
		{
			super( min, max );
		}

		@Override
		public void convert( final Volatile< T > input, final VolatileARGBType output )
		{
			final boolean isValid = input.isValid();
			output.setValid( isValid );
			if ( isValid )
			{
				final double a = input.get().getRealDouble();
				final int b = Math.min( 255, roundPositive( Math.max( 0, ( a - min ) / scale * 255.0 ) ) );
				final int argb = 0xff000000 | ( b << 8 | b ) << 8 | b;
				output.set( argb );
			}
		}

	}

	public static class ARGBConvertedSource< T > implements Source< VolatileARGBType >
	{
		final private AbstractViewerSetupImgLoader< T, ? extends Volatile< T > > loader;

		private final int setupId;

		private final Converter< Volatile< T >, VolatileARGBType > converter;

		final protected InterpolatorFactory< VolatileARGBType, RandomAccessible< VolatileARGBType > >[] interpolatorFactories;
		{
			interpolatorFactories = new InterpolatorFactory[] {
					new NearestNeighborInterpolatorFactory< VolatileARGBType >(),
					new ClampingNLinearInterpolatorFactory< VolatileARGBType >()
			};
		}

		public ARGBConvertedSource(
				final int setupId,
				final AbstractViewerSetupImgLoader< T, ? extends Volatile< T > > loader,
				final Converter< Volatile< T >, VolatileARGBType > converter )
		{
			this.setupId = setupId;
			this.loader = loader;
			this.converter = converter;
		}

		final public AbstractViewerSetupImgLoader< T, ? extends Volatile< T > > getLoader()
		{
			return loader;
		}

		@Override
		public RandomAccessibleInterval< VolatileARGBType > getSource( final int t, final int level )
		{
			return Converters.convert(
					loader.getVolatileImage( t, level ),
					converter,
					new VolatileARGBType() );
		}

		@Override
		public void getSourceTransform( final int t, final int level, final AffineTransform3D transform )
		{
			transform.set( loader.getMipmapTransforms()[ level ] );
		}

		/**
		 * TODO Store this in a field
		 */
		@Override
		public int getNumMipmapLevels()
		{
			return loader.getMipmapResolutions().length;
		}

		@Override
		public boolean isPresent( final int t )
		{
			return t == 0;
		}

		@Override
		public RealRandomAccessible< VolatileARGBType > getInterpolatedSource( final int t, final int level, final Interpolation method )
		{

			final ExtendedRandomAccessibleInterval< VolatileARGBType, RandomAccessibleInterval< VolatileARGBType > > extendedSource =
					Views.extendValue( getSource( t, level ), new VolatileARGBType( 0 ) );
			switch ( method )
			{
			case NLINEAR:
				return Views.interpolate( extendedSource, interpolatorFactories[ 1 ] );
			default:
				return Views.interpolate( extendedSource, interpolatorFactories[ 0 ] );
			}
		}

		@Override
		public VolatileARGBType getType()
		{
			return new VolatileARGBType();
		}

		@Override
		public String getName()
		{
			return "1 2 3";
		}

		@Override
		public VoxelDimensions getVoxelDimensions()
		{
			return null;
		}

		// TODO: make ARGBType version of this source
		public Source nonVolatile()
		{
			return this;
		}
	}

}
