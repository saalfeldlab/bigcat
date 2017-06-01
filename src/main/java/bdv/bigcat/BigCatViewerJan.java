package bdv.bigcat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.InputTriggerDescription;
import org.scijava.ui.behaviour.io.yaml.YamlConfigIO;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import bdv.AbstractViewerSetupImgLoader;
import bdv.BigDataViewer;
import bdv.ViewerSetupImgLoader;
import bdv.bigcat.BigCatViewerJan.DatasetSpecification.DataType;
import bdv.bigcat.composite.ARGBCompositeAlphaYCbCr;
import bdv.bigcat.composite.Composite;
import bdv.bigcat.composite.CompositeCopy;
import bdv.bigcat.composite.CompositeProjector;
import bdv.bigcat.control.TranslateZController;
import bdv.bigcat.ui.ARGBConvertedLabelsSource;
import bdv.bigcat.ui.ARGBStream;
import bdv.bigcat.ui.Util;
import bdv.bigcat.ui.highlighting.AbstractHighlightingARGBStream;
import bdv.bigcat.ui.highlighting.ModalGoldenAngleSaturatedHighlightingARGBStream;
import bdv.img.SetCache;
import bdv.img.h5.H5LabelMultisetSetupImageLoader;
import bdv.img.h5.H5UnsignedByteSetupImageLoader;
import bdv.labels.labelset.LabelMultisetType;
import bdv.labels.labelset.VolatileLabelMultisetType;
import bdv.spimdata.SequenceDescriptionMinimal;
import bdv.spimdata.SpimDataMinimal;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.RealARGBColorConverterSetup;
import bdv.viewer.DisplayMode;
import bdv.viewer.NavigationActions;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerFrame;
import bdv.viewer.ViewerOptions;
import bdv.viewer.ViewerPanel;
import bdv.viewer.render.AccumulateProjectorFactory;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import gnu.trove.set.hash.TLongHashSet;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import net.imglib2.display.ScaledARGBConverter;
import net.imglib2.display.ScaledARGBConverter.ARGB;
import net.imglib2.display.ScaledARGBConverter.VolatileARGB;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.volatiles.VolatileARGBType;

public class BigCatViewerJan< P extends BigCatViewerJan.Parameters >
{

	public static class DatasetSpecification
	{

		public static enum DataType
		{
			RAW, LABEL
		};

		private DataType dataType;

		private double[] resolution;

		private double[] offset;

		public DataType dataType()
		{
			return dataType;
		}

		public double[] resolution()
		{
			return resolution;
		}

		public double[] offset()
		{
			return offset;
		}

		public DatasetSpecification dataType( final DataType dataType )
		{
			this.dataType = dataType;
			return this;
		}

		public DatasetSpecification resolution( final double[] resolution )
		{
			this.resolution = resolution;
			return this;
		}

		public DatasetSpecification offset( final double[] offset )
		{
			this.offset = offset;
			return this;
		}

	}

	public static class H5Specification extends DatasetSpecification
	{
		private String path;

		private String dataset;

		private int[] cellSize;

		public String path()
		{
			return path;
		}

		public String dataset()
		{
			return dataset;
		}

		public int[] cellSize()
		{
			return cellSize;
		}

		public H5Specification path( final String path )
		{
			this.path = path;
			return this;
		}

		public H5Specification dataset( final String dataset )
		{
			this.dataset = dataset;
			return this;
		}

		public H5Specification cellSize( final int[] cellSize )
		{
			this.cellSize = cellSize;
			return this;
		}

	}

	static public class Parameters
	{
		@Parameter( names = { "--raw-file", "-r" }, description = "Path to raw hdf5" )
		public String rawFile = "";

		public Parameters setRawFile( final String rawFile )
		{
			this.rawFile = rawFile;
			return this;
		}

		@Parameter( names = { "--raw-dataset", "-R" }, description = "Raw dataset" )
		public String rawDataset = "volumes/raw";

		public Parameters setRawDataset( final String rawDataset )
		{
			this.rawDataset = rawDataset;
			return this;
		}

		@Parameter( names = { "--ground-truth-file", "-g" }, description = "Path to ground truth hdf5" )
		public String groundTruthFile = "";

		public Parameters setGroundTruthFile( final String groundTruthFile )
		{
			this.groundTruthFile = groundTruthFile;
			return this;
		}

		@Parameter( names = { "--ground-truth-dataset", "-G" }, description = "GT dataset" )
		public String groundTruthDataset = "volumes/labels/neuron_ids_notransparency";

		public Parameters setGroundTruthDataset( final String groundTruthDataset )
		{
			this.groundTruthFile = groundTruthDataset;
			return this;
		}

		@Parameter( names = { "--prediction-file", "-p" }, description = "Path to prediction hdf5" )
		public String predictionFile = "";

		public Parameters setPredictionFile( final String predictionFile )
		{
			this.predictionFile = predictionFile;
			return this;
		}

		@Parameter ( names = { "--prediction-dataset", "-P" }, description = "Prediction dataset" )
		public String predictionDataset = "main";

		public Parameters setPredictionDataset( final String predictionDataset )
		{
			this.predictionDataset = predictionDataset;
			return this;
		}

		@Parameter( names = { "--prediction-offset", "-o" }, description = "Offset of prediction (will read from h5 if not set and available)" )
		public String offset = null;

		public double[] offsetArray = null;

		public Parameters setOffsetArray( final double[] offsetArray )
		{
			this.offsetArray = offsetArray;
			return this;
		}

		@Parameter( names = { "--resolution" }, description = "Voxel resolution" )
		public String resolution = null;

		public double[] resolutionArray = null;

		public Parameters setResolutionArray( final double[] resolutionArray )
		{
			this.resolutionArray = resolutionArray;
			return this;
		}

		public void init()
		{
			if ( offset != null )
			{
				offsetArray = Arrays.stream( offset.split( "," ) ).mapToDouble( Double::parseDouble ).toArray();
				final double tmp = offsetArray[ 0 ];
				offsetArray[ 0 ] = offsetArray[ 2 ];
				offsetArray[ 2 ] = tmp;
			}

			if ( resolution != null )
			{
				resolutionArray = Arrays.stream( resolution.split( "," ) ).mapToDouble( Double::parseDouble ).toArray();
				final double tmp = resolutionArray[ 0 ];
				resolutionArray[ 0 ] = resolutionArray[ 2 ];
				resolutionArray[ 2 ] = tmp;
			}
		}

	}

	/** default cell dimensions */
	final static protected int[] cellDimensions = { 145, 53, 5 }; // new int[]{
	// 64, 64, 8
	// };

	/** loaded segments */
	final protected HashMap< DatasetSpecification, AbstractViewerSetupImgLoader< ?, ? > > loaders = new HashMap<>();

	BigDataViewer bdv;

	ViewerFrame frame;

	ViewerPanel viewer;

	/** controllers */
	protected InputTriggerConfig config;

	protected int setupId = 0;

	protected double[] resolution = null;

	protected double[] offset = null;

	private final ArrayList< DatasetSpecification > datasetSpecifications;

	private final HashMap< DatasetSpecification, Composite< ARGBType, ARGBType > > composites = new HashMap<>();

	private final HashMap< DatasetSpecification, SetCache > cacheLoaders = new HashMap<>();

	private final HashMap< DatasetSpecification, ARGBStream > colorStreams = new HashMap<>();

	public BigDataViewer getBigDataViewer()
	{
		return bdv;
	}

	public ViewerFrame getViewerFrame()
	{
		return frame;
	}

	public ViewerPanel getViewer()
	{
		return viewer;
	}

	public void highlight( final DatasetSpecification spec, final TLongHashSet highlights )
	{

		final ARGBStream stream = colorStreams.get( spec );

		if ( stream == null || !( stream instanceof AbstractHighlightingARGBStream ) )
			return;

		synchronized ( stream )
		{
			( ( AbstractHighlightingARGBStream ) stream ).highlight( highlights );
		}
		getViewer().requestRepaint();
	}

	public void highlight( final DatasetSpecification spec, final long[] highlights )
	{

		final ARGBStream stream = colorStreams.get( spec );

		if ( stream == null || !( stream instanceof AbstractHighlightingARGBStream ) )
			return;

		synchronized ( stream )
		{
			( ( AbstractHighlightingARGBStream ) stream ).highlight( highlights );
		}
		getViewer().requestRepaint();
	}

	public static void main( final String[] args ) throws Exception
	{
		final String[] argv = new String[] {
				"-r", "/groups/saalfeld/home/hanslovskyp/from_funkej/phil/sample_B.augmented.0.hdf",
				"-g", "/groups/saalfeld/home/hanslovskyp/from_funkej/phil/sample_B.augmented.0.hdf",
				"-p", "/groups/saalfeld/home/hanslovskyp/from_funkej/phil/sample_B_median_aff_cf_hq_dq_au00.87.hdf",
				"-o", "560,424,424", "--resolution", "40,4,4"
//				"-o", "14,106,106" // "560,424,424" resolution = 1?
		};
		final Parameters params = new Parameters();
		new JCommander( params, argv );
		params.init();

		final int[] cellSize = { 145, 53, 5 };

		final DatasetSpecification raw = new H5Specification()
				.cellSize( cellSize )
				.path( params.rawFile )
				.dataset( params.rawDataset )
				.resolution( params.resolutionArray )
				.offset( null )
				.dataType( DataType.RAW );

		final DatasetSpecification gt = new H5Specification()
				.cellSize( cellSize )
				.path( params.groundTruthFile )
				.dataset( params.groundTruthDataset )
				.resolution( params.resolutionArray )
				.offset( null )
				.dataType( DataType.LABEL );

		final DatasetSpecification gt2 = new H5Specification()
				.cellSize( cellSize )
				.path( params.groundTruthFile )
				.dataset( params.groundTruthDataset )
				.resolution( params.resolutionArray )
				.offset( null )
				.dataType( DataType.LABEL );

		final DatasetSpecification gt3 = new H5Specification()
				.cellSize( cellSize )
				.path( params.groundTruthFile )
				.dataset( params.groundTruthDataset )
				.resolution( params.resolutionArray )
				.offset( null )
				.dataType( DataType.LABEL );

		final DatasetSpecification prediction = new H5Specification()
				.cellSize( cellSize )
				.path( params.predictionFile )
				.dataset( params.predictionDataset )
				.resolution( params.resolutionArray )
				.offset( params.offsetArray )
				.dataType( DataType.LABEL );

		run( raw, gt, gt2, gt3, prediction );
	}

	public static BigCatViewerJan< Parameters > run( final DatasetSpecification... specs ) throws Exception
	{


		final BigCatViewerJan< Parameters > bigCat = new BigCatViewerJan<>( Arrays.asList( specs ) );
		bigCat.initData();
		bigCat.setupBdv();
		return bigCat;
	}

	public BigCatViewerJan( final List< DatasetSpecification > datasetSpecifications ) throws Exception
	{
		this.datasetSpecifications = new ArrayList<>( datasetSpecifications );
		Util.initUI();
		this.config = getInputTriggerConfig();
	}

	/**
	 * Load raw data and labels and initialize canvas
	 *
	 * @param params
	 * @throws IOException
	 */
	protected void initData() throws IOException
	{

		final ArrayList< DatasetSpecification > specs = this.datasetSpecifications;

		for ( int i = 0; i < specs.size(); ++i )
		{
			final DatasetSpecification spec = specs.get( i );

			if ( spec instanceof H5Specification )
			{
				final H5Specification h5spec = ( H5Specification ) spec;
				final String path = h5spec.path;
				final String dataset = h5spec.dataset;
				System.out.println( "Opening raw from " + path );
				final IHDF5Reader reader = HDF5Factory.open( path );

				/* raw pixels */
				final double[] resolution = spec.resolution == null ? readResolution( reader, dataset ) : spec.resolution;
				final double[] offset = spec.offset == null ? readOffset( reader, dataset ) : spec.offset;
				if ( reader.exists( dataset ) )
				{
					final int currentSetupId = setupId++;
					final AbstractViewerSetupImgLoader< ?, ? > loader;
					if ( spec.dataType.equals( DataType.RAW ) )
					{
						loader = new H5UnsignedByteSetupImageLoader( reader, dataset, currentSetupId, h5spec.cellSize(), resolution, offset );
						composites.put( spec, new CompositeCopy< ARGBType >() );
					}
					else
					{
						loader = new H5LabelMultisetSetupImageLoader( reader, null, dataset, currentSetupId, h5spec.cellSize(), resolution, offset );
						final ModalGoldenAngleSaturatedHighlightingARGBStream colorStream = new ModalGoldenAngleSaturatedHighlightingARGBStream();
						colorStream.setAlpha( 0x20 );
						this.colorStreams.put( h5spec, colorStream );
						composites.put( spec, new ARGBCompositeAlphaYCbCr() );
					}

					loaders.put( spec, loader );
				}
				else
					System.out.println( "no dataset '" + dataset + "' found" );
			}
		}
	}

	/**
	 * Create tool.
	 *
	 * Depends on {@link #raws}, {@link #labels},
	 * {@link #convertedLabelCanvasPairs}, {@link #colorStream},
	 * {@link #idService}, {@link #assignment},
	 * {@link #config}, {@link #dirtyLabelsInterval},
	 * {@link #completeFragmentsAssignment}, {@link #canvas} being initialized.
	 *
	 * Modifies {@link #bdv}, {@link #convertedLabelCanvasPairs},
	 * {@link #persistenceController},
	 *
	 * @param params
	 * @throws Exception
	 */
	protected void setupBdv() throws Exception
	{
		/* composites */
//		final ArrayList< Composite< ARGBType, ARGBType > > composites = new ArrayList<>();
//		final ArrayList< SetCache > cacheLoaders = new ArrayList<>();
//		for ( final H5UnsignedByteSetupImageLoader loader : raws )
//		{
//			composites.add( new CompositeCopy< ARGBType >() );
//			cacheLoaders.add( loader );
//		}
//		for ( final H5LabelMultisetSetupImageLoader loader : labels )
//		{
//			composites.add( new ARGBCompositeAlphaYCbCr() );
//			cacheLoaders.add( loader );
//		}

		final String windowTitle = "BigCAT";

		bdv = createViewer( windowTitle, this.datasetSpecifications, loaders, colorStreams, composites, config );

		frame = bdv.getViewerFrame();

		viewer = frame.getViewerPanel();

		viewer.setDisplayMode( DisplayMode.FUSED );

		frame.setVisible( true );

		NavigationActions.installActionBindings( frame.getKeybindings(), viewer, config );

		final TriggerBehaviourBindings bindings = frame.getTriggerbindings();

		/* override navigator z-step size with raw[ 0 ] z resolution */
		final TranslateZController translateZController = new TranslateZController(
				getViewer(),
				loaders.values().iterator().next().getMipmapResolutions()[ 0 ],
				config );
		bindings.addBehaviourMap( "translate_z", translateZController.getBehaviourMap() );

	}


	static protected InputTriggerConfig getInputTriggerConfig() throws IllegalArgumentException
	{
		final String[] filenames = { "bigcatkeyconfig.yaml", System.getProperty( "user.home" ) + "/.bdv/bigcatkeyconfig.yaml" };

		for ( final String filename : filenames )
			try
		{
				if ( new File( filename ).isFile() )
				{
					System.out.println( "reading key config from file " + filename );
					return new InputTriggerConfig( YamlConfigIO.read( filename ) );
				}
		}
		catch ( final IOException e )
		{
			System.err.println( "Error reading " + filename );
		}

		System.out.println( "creating default input trigger config" );

		// default input trigger config, disables "control button1" drag in bdv
		// (collides with default of "move annotation")
		final InputTriggerConfig config =
				new InputTriggerConfig(
						Arrays.asList(
								new InputTriggerDescription[] { new InputTriggerDescription( new String[] { "not mapped" }, "drag rotate slow", "bdv" ) } ) );

		return config;
	}

	static public double[] readResolution( final IHDF5Reader reader, final String dataset )
	{
		final double[] resolution;
		if ( reader.object().hasAttribute( dataset, "resolution" ) )
		{
			final double[] h5res = reader.float64().getArrayAttr( dataset, "resolution" );
			resolution = new double[] { h5res[ 2 ], h5res[ 1 ], h5res[ 0 ], };
		}
		else
			resolution = new double[] { 1, 1, 1 };

		return resolution;
	}

	static public double[] readOffset( final IHDF5Reader reader, final String dataset )
	{
		final double[] offset;
		if ( reader.object().hasAttribute( dataset, "offset" ) )
		{
			final double[] h5offset = reader.float64().getArrayAttr( dataset, "offset" );
			offset = new double[] { h5offset[ 2 ], h5offset[ 1 ], h5offset[ 0 ], };
		}
		else
			offset = new double[] { 0, 0, 0 };

		return offset;
	}

	public static < A extends ViewerSetupImgLoader< ? extends NumericType< ? >, ? > & SetCache > BigDataViewer createViewer(
			final String windowTitle,
			final List< DatasetSpecification > specs,
			final HashMap< DatasetSpecification, AbstractViewerSetupImgLoader< ?, ? > > loaders,
			final HashMap< DatasetSpecification, ARGBStream > streams,
			final HashMap< DatasetSpecification, Composite< ARGBType, ARGBType > > composites,
			final InputTriggerConfig config )
	{

		int setupId = 0;
		final ArrayList< CombinedImgLoader.SetupIdAndLoader > rawLoaders = new ArrayList<>();
		for ( final DatasetSpecification spec : specs )
			if ( spec.dataType.equals( DataType.RAW ) )
			{
				rawLoaders.add( new CombinedImgLoader.SetupIdAndLoader( setupId, loaders.get( spec ) ) );
				++setupId;
			}

		final CombinedImgLoader imgLoader = new CombinedImgLoader( rawLoaders.stream().toArray( CombinedImgLoader.SetupIdAndLoader[]::new ) );

		final ArrayList< TimePoint > timePointsList = new ArrayList<>();
		final Map< Integer, BasicViewSetup > setups = new HashMap<>();
		final ArrayList< ViewRegistration > viewRegistrationsList = new ArrayList<>();
		for ( final CombinedImgLoader.SetupIdAndLoader loader : rawLoaders )
		{
			timePointsList.add( new TimePoint( 0 ) );
			setups.put( loader.setupId, new BasicViewSetup( loader.setupId, null, null, null ) );
			viewRegistrationsList.add( new ViewRegistration( 0, loader.setupId ) );
		}

		final TimePoints timepoints = new TimePoints( timePointsList );
		final ViewRegistrations reg = new ViewRegistrations( viewRegistrationsList );
		final SequenceDescriptionMinimal seq = new SequenceDescriptionMinimal( timepoints, setups, imgLoader, null );
		final SpimDataMinimal spimData = new SpimDataMinimal( null, seq, reg );

		final ArrayList< SourceAndConverter< ? > > sources = new ArrayList<>();
		final ArrayList< ConverterSetup > converterSetups = new ArrayList<>();
		final HashMap< Source< ? >, Composite< ARGBType, ARGBType > > sourceCompositesMap = new HashMap<>();

		BigDataViewer.initSetups( spimData, converterSetups, sources );
		{
			int index = 0;
			for ( final DatasetSpecification spec : specs )
				if ( spec.dataType.equals( DataType.RAW ) )
					sourceCompositesMap.put( sources.get( index++ ).getSpimSource(), composites.get( spec ) );
		}

		for ( final DatasetSpecification spec : specs )
		{
			final AbstractViewerSetupImgLoader< ?, ? > loader = loaders.get( spec );
			if ( spec.dataType().equals( DataType.LABEL ) )
			{
				final ARGB converter = new ScaledARGBConverter.ARGB( 0, 255 );
				final VolatileARGB vconverter = new ScaledARGBConverter.VolatileARGB( 0, 255 );
				final AbstractViewerSetupImgLoader< LabelMultisetType, VolatileLabelMultisetType > l = ( AbstractViewerSetupImgLoader< LabelMultisetType, VolatileLabelMultisetType > ) loader;
				final ARGBConvertedLabelsSource source = new ARGBConvertedLabelsSource( setupId, l, streams.get( spec ) );
				final SourceAndConverter< VolatileARGBType > vsoc = new SourceAndConverter<>( source, vconverter );
				final SourceAndConverter< ARGBType > soc = new SourceAndConverter<>( source.nonVolatile(), converter, vsoc );
				sources.add( soc );
				sourceCompositesMap.put( soc.getSpimSource(), composites.get( spec ) );
				final RealARGBColorConverterSetup labelConverterSetup = new RealARGBColorConverterSetup( 2, converter, vconverter );
				converterSetups.add( labelConverterSetup );
				sourceCompositesMap.put( soc.getSpimSource(), composites.get( spec ) );
			}
		}


		/* cache */
		for ( final DatasetSpecification spec : specs )
			((SetCache)loaders.get( spec )).setCache( imgLoader.getCacheControl() );

		final AccumulateProjectorFactory< ARGBType > projectorFactory = new CompositeProjector.CompositeProjectorFactory< >( sourceCompositesMap );

		ViewerOptions options = ViewerOptions.options()
				.accumulateProjectorFactory( projectorFactory )
				.numRenderingThreads( 16 )
				.targetRenderNanos( 10000000 );

		if ( config != null )
			options = options.inputTriggerConfig( config );

		final BigDataViewer bdv = new BigDataViewer( converterSetups, sources, null, timepoints.size(), imgLoader.getCacheControl(), windowTitle, null, options );

		final AffineTransform3D transform = new AffineTransform3D();
		bdv.getViewer().setCurrentViewerTransform( transform );
		bdv.getViewer().setDisplayMode( DisplayMode.FUSED );

		/* separate source min max */
		for ( final ConverterSetup converterSetup : converterSetups )
		{
			bdv.getSetupAssignments().removeSetupFromGroup( converterSetup, bdv.getSetupAssignments().getMinMaxGroups().get( 0 ) );
			converterSetup.setDisplayRange( 0, 255 );
		}

		return bdv;


	}
}
