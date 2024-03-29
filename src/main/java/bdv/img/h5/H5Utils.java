package bdv.img.h5;

import static bdv.img.hdf5.Util.reorder;
import static net.imglib2.cache.img.AccessFlags.VOLATILE;
import static net.imglib2.cache.img.PrimitiveType.BYTE;
import static net.imglib2.cache.img.PrimitiveType.DOUBLE;
import static net.imglib2.cache.img.PrimitiveType.FLOAT;
import static net.imglib2.cache.img.PrimitiveType.INT;
import static net.imglib2.cache.img.PrimitiveType.LONG;
import static net.imglib2.cache.img.PrimitiveType.SHORT;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import bdv.bigcat.label.FragmentSegmentAssignment;
import bdv.labels.labelset.Label;
import bdv.labels.labelset.LabelMultiset;
import bdv.labels.labelset.LabelMultisetType;
import ch.systemsx.cisd.base.mdarray.MDByteArray;
import ch.systemsx.cisd.base.mdarray.MDDoubleArray;
import ch.systemsx.cisd.base.mdarray.MDFloatArray;
import ch.systemsx.cisd.base.mdarray.MDLongArray;
import ch.systemsx.cisd.base.mdarray.MDShortArray;
import ch.systemsx.cisd.hdf5.HDF5DataSetInformation;
import ch.systemsx.cisd.hdf5.HDF5DataTypeInformation;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.HDF5IntStorageFeatures;
import ch.systemsx.cisd.hdf5.HDF5StorageLayout;
import ch.systemsx.cisd.hdf5.IHDF5ByteReader;
import ch.systemsx.cisd.hdf5.IHDF5ByteWriter;
import ch.systemsx.cisd.hdf5.IHDF5DoubleReader;
import ch.systemsx.cisd.hdf5.IHDF5DoubleWriter;
import ch.systemsx.cisd.hdf5.IHDF5FloatReader;
import ch.systemsx.cisd.hdf5.IHDF5FloatWriter;
import ch.systemsx.cisd.hdf5.IHDF5LongReader;
import ch.systemsx.cisd.hdf5.IHDF5LongWriter;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import ch.systemsx.cisd.hdf5.IHDF5ShortReader;
import ch.systemsx.cisd.hdf5.IHDF5ShortWriter;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import gnu.trove.TLongCollection;
import gnu.trove.impl.Constants;
import gnu.trove.iterator.TLongIterator;
import gnu.trove.map.hash.TLongLongHashMap;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.ArrayDataAccessFactory;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.LoadedCellCacheLoader;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.img.basictypeaccess.array.LongArray;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.img.basictypeaccess.volatiles.VolatileAccess;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.img.cell.CellImg;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.cell.LazyCellImg;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.type.numeric.integer.GenericByteType;
import net.imglib2.type.numeric.integer.GenericIntType;
import net.imglib2.type.numeric.integer.GenericLongType;
import net.imglib2.type.numeric.integer.GenericShortType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.RandomAccessiblePair;
import net.imglib2.view.Views;

/**
 * Utility methods for simple HDF5 files.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
public class H5Utils
{
	static public void cropCellDimensions(
			final Dimensions sourceDimensions,
			final long[] offset,
			final int[] cellDimensions,
			final long[] croppedCellDimensions )
	{
		final int n = sourceDimensions.numDimensions();
		for ( int d = 0; d < n; ++d )
			croppedCellDimensions[ d ] = Math.min( cellDimensions[ d ], sourceDimensions.dimension( d ) - offset[ d ] );
	}

	static public void cropCellDimensions(
			final long[] max,
			final long[] offset,
			final int[] cellDimensions,
			final long[] croppedCellDimensions )
	{
		for ( int d = 0; d < max.length; ++d )
			croppedCellDimensions[ d ] = Math.min( cellDimensions[ d ], max[ d ] - offset[ d ] + 1 );
	}

	/**
	 * Load an HDF5 float32 dataset into a {@link CellImg} of {@link FloatType}.
	 *
	 * @param reader
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< FloatType, ? > loadFloat(
			final IHDF5Reader reader,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5FloatReader float32Reader = reader.float32();

		final long[] dimensions = reorder( reader.object().getDimensions( dataset ) );
		final int n = dimensions.length;

		final CellImg< FloatType, ? > target = new CellImgFactory< FloatType >( cellDimensions ).create( dimensions, new FloatType() );

		final long[] offset = new long[ n ];
		final long[] targetCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( target, offset, cellDimensions, targetCellDimensions );
			final RandomAccessibleInterval< FloatType > targetBlock = Views.offsetInterval( target, offset, targetCellDimensions );
			final MDFloatArray targetCell = float32Reader.readMDArrayBlockWithOffset(
					dataset,
					Util.long2int( reorder( targetCellDimensions ) ),
					reorder( offset ) );

			int i = 0;
			for ( final FloatType t : Views.flatIterable( targetBlock ) )
				t.set( targetCell.get( i++ ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] < dimensions[ d ] )
					break;
				else
					offset[ d ] = 0;
			}
		}

		return target;
	}

	/**
	 * Load an HDF5 float32 dataset into a {@link CellImg} of {@link FloatType}.
	 *
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< FloatType, ? > loadFloat(
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( file );
		final CellImg< FloatType, ? > target = loadFloat( reader, dataset, cellDimensions );
		reader.close();
		return target;
	}

	/**
	 * Load an HDF5 float32 dataset into a {@link CellImg} of {@link FloatType}.
	 *
	 * @param filePath
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< FloatType, ? > loadFloat(
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		return loadFloat( new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Load an HDF5 float64 dataset into a {@link CellImg} of
	 * {@link DoubleType}.
	 *
	 * @param reader
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< DoubleType, ? > loadDouble(
			final IHDF5Reader reader,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5DoubleReader float64Reader = reader.float64();

		final long[] dimensions = reorder( reader.object().getDimensions( dataset ) );
		final int n = dimensions.length;

		final CellImg< DoubleType, ? > target = new CellImgFactory< DoubleType >( cellDimensions ).create( dimensions, new DoubleType() );

		final long[] offset = new long[ n ];
		final long[] targetCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( target, offset, cellDimensions, targetCellDimensions );
			final RandomAccessibleInterval< DoubleType > targetBlock = Views.offsetInterval( target, offset, targetCellDimensions );
			final MDDoubleArray targetCell = float64Reader.readMDArrayBlockWithOffset(
					dataset,
					Util.long2int( reorder( targetCellDimensions ) ),
					reorder( offset ) );

			int i = 0;
			for ( final DoubleType t : Views.flatIterable( targetBlock ) )
				t.set( targetCell.get( i++ ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] < dimensions[ d ] )
					break;
				else
					offset[ d ] = 0;
			}
		}

		return target;
	}

	/**
	 * Load an HDF5 float64 dataset into a {@link CellImg} of
	 * {@link DoubleType}.
	 *
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< DoubleType, ? > loadDouble(
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( file );
		final CellImg< DoubleType, ? > target = loadDouble( reader, dataset, cellDimensions );
		reader.close();
		return target;
	}

	/**
	 * Load an HDF5 float64 dataset into a {@link CellImg} of
	 * {@link DoubleType}.
	 *
	 * @param filePath
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< DoubleType, ? > loadDouble(
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		return loadDouble( new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Load an HDF5 uint64 dataset into a {@link CellImg} of {@link LongType}.
	 *
	 * @param reader
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< LongType, ? > loadUnsignedLong(
			final IHDF5Reader reader,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5LongReader uint64Reader = reader.uint64();

		final long[] dimensions = reorder( reader.object().getDimensions( dataset ) );
		final int n = dimensions.length;

		final CellImg< LongType, ? > target = new CellImgFactory< LongType >( cellDimensions ).create( dimensions, new LongType() );

		final long[] offset = new long[ n ];
		final long[] targetCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( target, offset, cellDimensions, targetCellDimensions );
			final RandomAccessibleInterval< LongType > targetBlock = Views.offsetInterval( target, offset, targetCellDimensions );
			final MDLongArray targetCell = uint64Reader.readMDArrayBlockWithOffset(
					dataset,
					Util.long2int( reorder( targetCellDimensions ) ),
					reorder( offset ) );

			int i = 0;
			for ( final LongType t : Views.flatIterable( targetBlock ) )
				t.set( targetCell.get( i++ ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] < dimensions[ d ] )
					break;
				else
					offset[ d ] = 0;
			}
		}

		return target;
	}

	/**
	 * Load an HDF5 uint64 dataset into a {@link CellImg} of {@link LongType}.
	 *
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< LongType, ? > loadUnsignedLong(
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( file );
		final CellImg< LongType, ? > target = loadUnsignedLong( reader, dataset, cellDimensions );
		reader.close();
		return target;
	}

	/**
	 * Load an HDF5 uint64 dataset into a {@link CellImg} of {@link LongType}.
	 *
	 * @param filePath
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< LongType, ? > loadUnsignedLong(
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		return loadUnsignedLong( new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Load an HDF5 uint16 dataset into a {@link CellImg} of
	 * {@link UnsignedShortType}.
	 *
	 * @param reader
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< UnsignedShortType, ? > loadUnsignedShort(
			final IHDF5Reader reader,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5ShortReader uint16Reader = reader.uint16();

		final long[] dimensions = reorder( reader.object().getDimensions( dataset ) );
		final int n = dimensions.length;

		final CellImg< UnsignedShortType, ? > target = new CellImgFactory< UnsignedShortType >( cellDimensions ).create( dimensions, new UnsignedShortType() );

		final long[] offset = new long[ n ];
		final long[] targetCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( target, offset, cellDimensions, targetCellDimensions );
			final RandomAccessibleInterval< UnsignedShortType > targetBlock = Views.offsetInterval( target, offset, targetCellDimensions );
			final MDShortArray targetCell = uint16Reader.readMDArrayBlockWithOffset(
					dataset,
					Util.long2int( reorder( targetCellDimensions ) ),
					reorder( offset ) );

			int i = 0;
			for ( final UnsignedShortType t : Views.flatIterable( targetBlock ) )
				t.set( targetCell.get( i++ ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] < dimensions[ d ] )
					break;
				else
					offset[ d ] = 0;
			}
		}

		return target;
	}

	/**
	 * Load an HDF5 uint16 dataset into a {@link CellImg} of
	 * {@link UnsignedShortType}.
	 *
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< UnsignedShortType, ? > loadUnsignedShort(
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( file );
		final CellImg< UnsignedShortType, ? > target = loadUnsignedShort( reader, dataset, cellDimensions );
		reader.close();
		return target;
	}

	/**
	 * Load an HDF5 uint16 dataset into a {@link CellImg} of
	 * {@link UnsignedShortType}.
	 *
	 * @param filePath
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< UnsignedShortType, ? > loadUnsignedShort(
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		return loadUnsignedShort( new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Load an HDF5 uint8 dataset into a {@link CellImg} of
	 * {@link UnsignedByteType}.
	 *
	 * @param reader
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< UnsignedByteType, ? > loadUnsignedByte(
			final IHDF5Reader reader,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5ByteReader uint8Reader = reader.uint8();

		final long[] dimensions = reorder( reader.object().getDimensions( dataset ) );
		final int n = dimensions.length;

		final CellImg< UnsignedByteType, ? > target = new CellImgFactory< UnsignedByteType >( cellDimensions ).create( dimensions, new UnsignedByteType() );

		final long[] offset = new long[ n ];
		final long[] targetCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( target, offset, cellDimensions, targetCellDimensions );
			final RandomAccessibleInterval< UnsignedByteType > targetBlock = Views.offsetInterval( target, offset, targetCellDimensions );
			final MDByteArray targetCell = uint8Reader.readMDArrayBlockWithOffset(
					dataset,
					Util.long2int( reorder( targetCellDimensions ) ),
					reorder( offset ) );

			int i = 0;
			for ( final UnsignedByteType t : Views.flatIterable( targetBlock ) )
				t.set( targetCell.get( i++ ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] < dimensions[ d ] )
					break;
				else
					offset[ d ] = 0;
			}
		}

		return target;
	}

	/**
	 * Load an HDF5 uint8 dataset into a {@link CellImg} of
	 * {@link UnsignedByteType}.
	 *
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< UnsignedByteType, ? > loadUnsignedByte(
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( file );
		final CellImg< UnsignedByteType, ? > target = loadUnsignedByte( reader, dataset, cellDimensions );
		reader.close();
		return target;
	}

	/**
	 * Load an HDF5 uint8 dataset into a {@link CellImg} of
	 * {@link UnsignedByteType}.
	 *
	 * @param filePath
	 * @param dataset
	 * @param cellDimensions
	 */
	static public CellImg< UnsignedByteType, ? > loadUnsignedByte(
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		return loadUnsignedByte( new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link LongType} into an HDF5
	 * uint8 dataset.
	 *
	 * @param source
	 *            source
	 * @param dimensions
	 *            dimensions of the dataset if created new
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void createUnsignedByte(
			final IHDF5Writer writer,
			final String dataset,
			final Dimensions datasetDimensions,
			final int[] cellDimensions )
	{
		final IHDF5ByteWriter uint8Writer = writer.uint8();

		if ( writer.exists( dataset ) )
			writer.delete( dataset );

		uint8Writer.createMDArray(
				dataset,
				reorder( Intervals.dimensionsAsLongArray( datasetDimensions ) ),
				reorder( cellDimensions ),
				HDF5IntStorageFeatures.INT_AUTO_SCALING_DEFLATE );

	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link UnsignedByteType} into
	 * an HDF5 uint8 dataset.
	 *
	 * @param source
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveUnsignedByte(
			final RandomAccessibleInterval< UnsignedByteType > source,
			final IHDF5Writer writer,
			final String dataset,
			final int[] cellDimensions )
	{
		if ( !writer.exists( dataset ) )
			createUnsignedByte( writer, dataset, source, cellDimensions );

		final long[] dimensions = reorder( writer.object().getDimensions( dataset ) );
		final int n = source.numDimensions();

		final IHDF5ByteWriter uint8Writer = writer.uint8();

		/* min is >= 0, max is < dimensions */
		final long[] min = Intervals.minAsLongArray( source );
		final long[] max = Intervals.maxAsLongArray( source );
		for ( int d = 0; d < min.length; ++d )
		{
			min[ d ] = Math.max( 0, min[ d ] );
			max[ d ] = Math.min( dimensions[ d ] - 1, max[ d ] );
		}

		final long[] offset = min.clone();
		final long[] sourceCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( max, offset, cellDimensions, sourceCellDimensions );
			final RandomAccessibleInterval< UnsignedByteType > sourceBlock = Views.offsetInterval( source, offset, sourceCellDimensions );
			final MDByteArray targetCell = new MDByteArray( reorder( sourceCellDimensions ) );
			int i = 0;
			for ( final UnsignedByteType t : Views.flatIterable( sourceBlock ) )
				targetCell.set( UnsignedByteType.getCodedSignedByte( t.get() ), i++ );

			uint8Writer.writeMDArrayBlockWithOffset( dataset, targetCell, reorder( offset ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] <= max[ d ] )
					break;
				else
					offset[ d ] = min[ d ];
			}
		}
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link UnsignedByteType} into
	 * an HDF5 uint8 dataset.
	 *
	 * @param source
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveUnsignedByte(
			final RandomAccessibleInterval< UnsignedByteType > source,
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveUnsignedByte( source, writer, dataset, cellDimensions );
		writer.close();
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link UnsignedByteType} into
	 * an HDF5 uint8 dataset.
	 *
	 * @param source
	 * @param filePAth
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveUnsignedByte(
			final RandomAccessibleInterval< UnsignedByteType > source,
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		saveUnsignedByte( source, new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link FloatType} into an HDF5
	 * float32 dataset.
	 *
	 * @param source
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveFloat(
			final RandomAccessibleInterval< FloatType > source,
			final IHDF5Writer writer,
			final String dataset,
			final int[] cellDimensions )
	{
		final int n = source.numDimensions();
		final long[] dimensions = Intervals.dimensionsAsLongArray( source );
		final IHDF5FloatWriter float32Writer = writer.float32();
		if ( !writer.exists( dataset ) )
			float32Writer.createMDArray(
					dataset,
					reorder( dimensions ),
					reorder( cellDimensions ) );

		final long[] offset = new long[ n ];
		final long[] sourceCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( source, offset, cellDimensions, sourceCellDimensions );
			final RandomAccessibleInterval< FloatType > sourceBlock = Views.offsetInterval( source, offset, sourceCellDimensions );
			final MDFloatArray targetCell = new MDFloatArray( reorder( sourceCellDimensions ) );
			int i = 0;
			for ( final FloatType t : Views.flatIterable( sourceBlock ) )
				targetCell.set( t.get(), i++ );

			float32Writer.writeMDArrayBlockWithOffset( dataset, targetCell, reorder( offset ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] < source.dimension( d ) )
					break;
				else
					offset[ d ] = 0;
			}
		}
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link FloatType} into an HDF5
	 * float32 dataset.
	 *
	 * @param source
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveFloat(
			final RandomAccessibleInterval< FloatType > source,
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveFloat( source, writer, dataset, cellDimensions );
		writer.close();
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link FloatType} into an HDF5
	 * float32 dataset.
	 *
	 * @param source
	 * @param filePAth
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveFloat(
			final RandomAccessibleInterval< FloatType > source,
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		saveFloat( source, new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link DoubleType} into an
	 * HDF5 float64 dataset.
	 *
	 * @param source
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveDouble(
			final RandomAccessibleInterval< DoubleType > source,
			final IHDF5Writer writer,
			final String dataset,
			final int[] cellDimensions )
	{
		final int n = source.numDimensions();
		final long[] dimensions = Intervals.dimensionsAsLongArray( source );
		final IHDF5DoubleWriter float64Writer = writer.float64();
		if ( !writer.exists( dataset ) )
			float64Writer.createMDArray(
					dataset,
					reorder( dimensions ),
					reorder( cellDimensions ) );

		final long[] offset = new long[ n ];
		final long[] sourceCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( source, offset, cellDimensions, sourceCellDimensions );
			final RandomAccessibleInterval< DoubleType > sourceBlock = Views.offsetInterval( source, offset, sourceCellDimensions );
			final MDDoubleArray targetCell = new MDDoubleArray( reorder( sourceCellDimensions ) );
			int i = 0;
			for ( final DoubleType t : Views.flatIterable( sourceBlock ) )
				targetCell.set( t.get(), i++ );

			float64Writer.writeMDArrayBlockWithOffset( dataset, targetCell, reorder( offset ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] < source.dimension( d ) )
					break;
				else
					offset[ d ] = 0;
			}
		}
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link DoubleType} into an
	 * HDF5 float64 dataset.
	 *
	 * @param source
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveDouble(
			final RandomAccessibleInterval< DoubleType > source,
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveDouble( source, writer, dataset, cellDimensions );
		writer.close();
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link DoubleType} into an
	 * HDF5 float64 dataset.
	 *
	 * @param source
	 * @param filePAth
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveDouble(
			final RandomAccessibleInterval< DoubleType > source,
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		saveDouble( source, new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link ShortType} into an HDF5
	 * uint16 dataset.
	 *
	 * @param source
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveUnsignedShort(
			final RandomAccessibleInterval< ShortType > source,
			final IHDF5Writer writer,
			final String dataset,
			final int[] cellDimensions )
	{
		final int n = source.numDimensions();
		final long[] dimensions = Intervals.dimensionsAsLongArray( source );
		final IHDF5ShortWriter uint16Writer = writer.uint16();
		if ( !writer.exists( dataset ) )
			uint16Writer.createMDArray(
					dataset,
					reorder( dimensions ),
					reorder( cellDimensions ),
					HDF5IntStorageFeatures.INT_AUTO_SCALING_DEFLATE );

		final long[] offset = new long[ n ];
		final long[] sourceCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( source, offset, cellDimensions, sourceCellDimensions );
			final RandomAccessibleInterval< ShortType > sourceBlock = Views.offsetInterval( source, offset, sourceCellDimensions );
			final MDShortArray targetCell = new MDShortArray( reorder( sourceCellDimensions ) );
			int i = 0;
			for ( final ShortType t : Views.flatIterable( sourceBlock ) )
				targetCell.set( t.get(), i++ );

			uint16Writer.writeMDArrayBlockWithOffset( dataset, targetCell, reorder( offset ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] < source.dimension( d ) )
					break;
				else
					offset[ d ] = 0;
			}
		}
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link ShortType} into an HDF5
	 * uint16 dataset.
	 *
	 * @param source
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveUnsignedShort(
			final RandomAccessibleInterval< ShortType > source,
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveUnsignedShort( source, writer, dataset, cellDimensions );
		writer.close();
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link ShortType} into an HDF5
	 * uint16 dataset.
	 *
	 * @param source
	 * @param filePAth
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveUnsignedShort(
			final RandomAccessibleInterval< ShortType > source,
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		saveUnsignedShort( source, new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link LongType} into an HDF5
	 * uint64 dataset.
	 *
	 * @param source
	 *            source
	 * @param dimensions
	 *            dimensions of the dataset if created new
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void createUnsignedLong(
			final IHDF5Writer writer,
			final String dataset,
			final Dimensions datasetDimensions,
			final int[] cellDimensions )
	{
		final IHDF5LongWriter uint64Writer = writer.uint64();

		if ( writer.exists( dataset ) )
			writer.delete( dataset );

		uint64Writer.createMDArray(
				dataset,
				reorder( Intervals.dimensionsAsLongArray( datasetDimensions ) ),
				reorder( cellDimensions ),
				HDF5IntStorageFeatures.INT_AUTO_SCALING_DEFLATE );
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link LongType} into an HDF5
	 * uint64 dataset.
	 *
	 * @param source
	 *            source
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public <T extends IntegerType<T>> void saveUnsignedLong(
			final RandomAccessibleInterval<T> source,
			final IHDF5Writer writer,
			final String dataset,
			final int[] cellDimensions )
	{
		if ( !writer.exists( dataset ) )
			createUnsignedLong( writer, dataset, source, cellDimensions );

		final long[] dimensions = reorder( writer.object().getDimensions( dataset ) );
		final int n = source.numDimensions();

		final IHDF5LongWriter uint64Writer = writer.uint64();

		/* min is >= 0, max is < dimensions */
		final long[] min = Intervals.minAsLongArray( source );
		final long[] max = Intervals.maxAsLongArray( source );
		for ( int d = 0; d < min.length; ++d )
		{
			min[ d ] = Math.max( 0, min[ d ] );
			max[ d ] = Math.min( dimensions[ d ] - 1, max[ d ] );
		}

		final long[] offset = min.clone();
		final long[] sourceCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( max, offset, cellDimensions, sourceCellDimensions );
			final RandomAccessibleInterval<T> sourceBlock = Views.offsetInterval( source, offset, sourceCellDimensions );
			final MDLongArray targetCell = new MDLongArray( reorder( sourceCellDimensions ) );
			int i = 0;
			for ( final T t : Views.flatIterable( sourceBlock ) )
				targetCell.set( t.getIntegerLong(), i++ );

			uint64Writer.writeMDArrayBlockWithOffset( dataset, targetCell, reorder( offset ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] <= max[ d ] )
					break;
				else
					offset[ d ] = min[ d ];
			}
		}
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link LongType} into an HDF5
	 * uint64 dataset.
	 *
	 * @param source
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public <T extends IntegerType<T>> void saveUnsignedLong(
			final RandomAccessibleInterval<T> source,
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveUnsignedLong( source, writer, dataset, cellDimensions );
		writer.close();
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link LongType} into an HDF5
	 * uint64 dataset.
	 *
	 * @param source
	 * @param filePAth
	 * @param dataset
	 * @param cellDimensions
	 */
	static public <T extends IntegerType<T>> void saveUnsignedLong(
			final RandomAccessibleInterval<T> source,
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		saveUnsignedLong( source, new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Create anHDF5 int64 dataset.
	 *
	 * @param source
	 *            source
	 * @param dimensions
	 *            dimensions of the dataset if created new
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void createLong(
			final IHDF5Writer writer,
			final String dataset,
			final Dimensions datasetDimensions,
			final int[] cellDimensions )
	{
		final IHDF5LongWriter int64Writer = writer.int64();

		if ( writer.exists( dataset ) )
			writer.delete( dataset );

		int64Writer.createMDArray(
				dataset,
				reorder( Intervals.dimensionsAsLongArray( datasetDimensions ) ),
				reorder( cellDimensions ),
				HDF5IntStorageFeatures.INT_AUTO_SCALING_DEFLATE );
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link LongType} into an HDF5
	 * int64 dataset.
	 *
	 * @param source
	 *            source
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveLong(
			final RandomAccessibleInterval< LongType > source,
			final IHDF5Writer writer,
			final String dataset,
			final int[] cellDimensions )
	{
		if ( !writer.exists( dataset ) )
			createLong( writer, dataset, source, cellDimensions );

		final long[] dimensions = reorder( writer.object().getDimensions( dataset ) );
		final int n = source.numDimensions();

		final IHDF5LongWriter int64Writer = writer.int64();

		/* min is >= 0, max is < dimensions */
		final long[] min = Intervals.minAsLongArray( source );
		final long[] max = Intervals.maxAsLongArray( source );
		for ( int d = 0; d < min.length; ++d )
		{
			min[ d ] = Math.max( 0, min[ d ] );
			max[ d ] = Math.min( dimensions[ d ] - 1, max[ d ] );
		}

		final long[] offset = min.clone();
		final long[] sourceCellDimensions = new long[ n ];
		for ( int d = 0; d < n; )
		{
			cropCellDimensions( max, offset, cellDimensions, sourceCellDimensions );
			final RandomAccessibleInterval< LongType > sourceBlock = Views.offsetInterval( source, offset, sourceCellDimensions );
			final MDLongArray targetCell = new MDLongArray( reorder( sourceCellDimensions ) );
			int i = 0;
			for ( final LongType t : Views.flatIterable( sourceBlock ) )
				targetCell.set( t.get(), i++ );

			int64Writer.writeMDArrayBlockWithOffset( dataset, targetCell, reorder( offset ) );

			for ( d = 0; d < n; ++d )
			{
				offset[ d ] += cellDimensions[ d ];
				if ( offset[ d ] <= max[ d ] )
					break;
				else
					offset[ d ] = min[ d ];
			}
		}
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link LongType} into an HDF5
	 * int64 dataset.
	 *
	 * @param source
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveLong(
			final RandomAccessibleInterval< LongType > source,
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveLong( source, writer, dataset, cellDimensions );
		writer.close();
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link LongType} into an HDF5
	 * int64 dataset.
	 *
	 * @param source
	 * @param filePAth
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveLong(
			final RandomAccessibleInterval< LongType > source,
			final String filePath,
			final String dataset,
			final int[] cellDimensions )
	{
		saveLong( source, new File( filePath ), dataset, cellDimensions );
	}

	/**
	 * Save the combination of a single element {@link LabelMultiset} source and
	 * a {@link LongType} overlay with transparent pixels into an HDF5 uint64
	 * dataset.
	 *
	 * @param labelMultisetSource
	 *            the background
	 * @param labelSource
	 *            the overlay
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveSingleElementLabelMultisetLongPair(
			final RandomAccessible< LabelMultisetType > labelMultisetSource,
			final RandomAccessible< LongType > labelSource,
			final Interval interval,
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		assert labelMultisetSource.numDimensions() == labelSource.numDimensions() &&
				labelSource.numDimensions() == interval.numDimensions(): "input dimensions do not match";

		final RandomAccessiblePair< LabelMultisetType, LongType > pair = new RandomAccessiblePair<>( labelMultisetSource, labelSource );
		final RandomAccessibleInterval< Pair< LabelMultisetType, LongType > > pairInterval = Views.offsetInterval( pair, interval );
		final Converter< Pair< LabelMultisetType, LongType >, LongType > converter =
				( input, output ) -> {
					final long inputB = input.getB().get();
					if ( inputB == Label.TRANSPARENT )
						output.set( input.getA().entrySet().iterator().next().getElement().id() );
					else
						output.set( inputB );
				};

		final RandomAccessibleInterval< LongType > source =
				Converters.convert(
						pairInterval,
						converter,
						new LongType() );

		saveUnsignedLong( source, file, dataset, cellDimensions );
	}

	/**
	 * Save the combination of a single element {@link LabelMultiset} source and
	 * a fragment to segment assignment table and a {@link LongType} overlay
	 * with transparent pixels into an HDF5 uint64 dataset.
	 *
	 * @param labelMultisetSource
	 *            the background
	 * @param labelSource
	 *            the overlay
	 * @param interval
	 *            the interval to be saved
	 * @param assignment
	 *            fragment to segment assignment
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveAssignedSingleElementLabelMultisetLongPair(
			final RandomAccessible< LabelMultisetType > labelMultisetSource,
			final RandomAccessible< LongType > labelSource,
			final Interval interval,
			final FragmentSegmentAssignment assignment,
			final File file,
			final String dataset,
			final int[] cellDimensions )
	{
		assert labelMultisetSource.numDimensions() == labelSource.numDimensions() &&
				labelSource.numDimensions() == interval.numDimensions(): "input dimensions do not match";

		final RandomAccessiblePair< LabelMultisetType, LongType > pair = new RandomAccessiblePair<>( labelMultisetSource, labelSource );
		final RandomAccessibleInterval< Pair< LabelMultisetType, LongType > > pairInterval = Views.offsetInterval( pair, interval );
		final Converter< Pair< LabelMultisetType, LongType >, LongType > converter =
				( input, output ) -> {
					final long inputB = input.getB().get();
					if ( inputB == Label.TRANSPARENT )
						output.set( assignment.getSegment( input.getA().entrySet().iterator().next().getElement().id() ) );
					else
						output.set( assignment.getSegment( inputB ) );
				};

		final RandomAccessibleInterval< LongType > source =
				Converters.convert(
						pairInterval,
						converter,
						new LongType() );

		saveUnsignedLong( source, file, dataset, cellDimensions );
	}

	/**
	 * Load a long to long lookup table from an HDF5 dataset
	 *
	 * @param reader
	 * @param dataset
	 * @param blockSize
	 */
	static public TLongLongHashMap loadLongLongLut(
			final IHDF5Reader reader,
			final String dataset,
			final int blockSize )
	{
		final IHDF5LongReader uint64Reader = reader.uint64();

		if ( !reader.exists( dataset ) )
			return null;

		final long[] dimensions = reader.object().getDimensions( dataset );
		if ( !( dimensions.length == 2 && dimensions[ 0 ] == 2 ) )
		{
			System.err.println( "LUT is not a lookup table, dimensions = " + Arrays.toString( dimensions ) );
			return null;
		}

		final long size = dimensions[ 1 ];

		final TLongLongHashMap lut = new TLongLongHashMap(
				Constants.DEFAULT_CAPACITY,
				Constants.DEFAULT_LOAD_FACTOR,
				Label.TRANSPARENT,
				Label.TRANSPARENT );

		for ( int offset = 0; offset < size; offset += blockSize )
		{
			final MDLongArray block = uint64Reader.readMDArrayBlockWithOffset(
					dataset,
					new int[] { 2, ( int ) Math.min( blockSize, size - offset ) },
					new long[] { 0, offset } );

			for ( int i = 0; i < block.size( 1 ); ++i )
				lut.put( block.get( 0, i ), block.get( 1, i ) );

		}

		return lut;
	}

	/**
	 * Load a long to long lookup table from an HDF5 dataset.
	 *
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public TLongLongHashMap loadLongLongLut(
			final File file,
			final String dataset,
			final int blockSize )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( file );
		final TLongLongHashMap lut = loadLongLongLut( reader, dataset, blockSize );
		reader.close();
		return lut;
	}

	/**
	 * Load a long to long lookup table from an HDF5 dataset.
	 *
	 * @param filePath
	 * @param dataset
	 * @param cellDimensions
	 */
	static public TLongLongHashMap loadLongLongLut(
			final String filePath,
			final String dataset,
			final int blockSize )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( filePath );
		final TLongLongHashMap lut = loadLongLongLut( reader, dataset, blockSize );
		reader.close();
		return lut;
	}

	/**
	 * Save a long to long lookup table into an HDF5 uint64 dataset.
	 *
	 * @param lut
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveLongLongLut(
			final TLongLongHashMap lut,
			final IHDF5Writer writer,
			final String dataset,
			final int blockSize )
	{
		final IHDF5LongWriter uint64Writer = writer.uint64();
		if ( !writer.exists( dataset ) )
			uint64Writer.createMDArray(
					dataset,
					new long[] { 2, lut.size() },
					new int[] { 2, blockSize },
					HDF5IntStorageFeatures.INT_AUTO_SCALING_DEFLATE );

		final long[] keys = lut.keys();
		for ( int offset = 0, i = 0; offset < lut.size(); offset += blockSize )
		{
			final int size = Math.min( blockSize, lut.size() - offset );
			final MDLongArray targetCell = new MDLongArray( new int[] { 2, size } );
			for ( int j = 0; j < size; ++j, ++i )
			{
				targetCell.set( keys[ i ], 0, j );
				targetCell.set( lut.get( keys[ i ] ), 1, j );
			}

			uint64Writer.writeMDArrayBlockWithOffset( dataset, targetCell, new long[] { 0, offset } );
		}
	}

	/**
	 * Save a long to long lookup table into an HDF5 uint64 dataset.
	 *
	 * @param lut
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveLongLongLut(
			final TLongLongHashMap lut,
			final File file,
			final String dataset,
			final int blockSize )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveLongLongLut( lut, writer, dataset, blockSize );
		writer.close();
	}

	/**
	 * Save a long to long lookup table into an HDF5 uint64 dataset.
	 *
	 * @param lut
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveLongLongLut(
			final TLongLongHashMap lut,
			final String filePath,
			final String dataset,
			final int blockSize )
	{
		final IHDF5Writer writer = HDF5Factory.open( filePath );
		saveLongLongLut( lut, writer, dataset, blockSize );
		writer.close();
	}

	/**
	 * Load a long collection from an HDF5 dataset
	 *
	 * @param collection
	 * @param reader
	 * @param dataset
	 * @param blockSize
	 */
	static public boolean loadLongCollection(
			final TLongCollection collection,
			final IHDF5Reader reader,
			final String dataset,
			final int blockSize )
	{
		final IHDF5LongReader uint64Reader = reader.uint64();

		if ( !reader.exists( dataset ) )
			return false;

		final long[] dimensions = reader.object().getDimensions( dataset );
		if ( dimensions.length != 1 )
		{
			System.err.println( "Dataset is not a collection, dimensions = " + Arrays.toString( dimensions ) );
			return false;
		}

		final long size = dimensions[ 0 ];

		for ( int offset = 0; offset < size; offset += blockSize )
		{
			final MDLongArray block = uint64Reader.readMDArrayBlockWithOffset(
					dataset,
					new int[] { ( int ) Math.min( blockSize, size - offset ) },
					new long[] { offset } );

			for ( int i = 0; i < block.size( 0 ); ++i )
				collection.add( block.get( i ) );
		}

		return true;
	}

	/**
	 * Load a long collection from an HDF5 dataset
	 *
	 * @param collection
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public boolean loadLongCollection(
			final TLongCollection collection,
			final File file,
			final String dataset,
			final int blockSize )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( file );
		final boolean success = loadLongCollection( collection, reader, dataset, blockSize );
		reader.close();
		return success;
	}

	/**
	 * Load a long collection from an HDF5 dataset
	 *
	 * @param collection
	 * @param filePath
	 * @param dataset
	 * @param cellDimensions
	 */
	static public boolean loadLongCollection(
			final TLongCollection collection,
			final String filePath,
			final String dataset,
			final int blockSize )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( filePath );
		final boolean success = loadLongCollection( collection, reader, dataset, blockSize );
		reader.close();
		return success;
	}

	/**
	 * Save a long collection into an HDF5 uint64 dataset.
	 *
	 * @param lut
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveLongCollection(
			final TLongCollection longs,
			final IHDF5Writer writer,
			final String dataset,
			final int blockSize )
	{
		final IHDF5LongWriter uint64Writer = writer.uint64();
		if ( !writer.exists( dataset ) )
			uint64Writer.createMDArray(
					dataset,
					new long[] { longs.size() },
					new int[] { blockSize },
					HDF5IntStorageFeatures.INT_AUTO_SCALING_DEFLATE );

		final TLongIterator iterator = longs.iterator();

		for ( int offset = 0; offset < longs.size(); offset += blockSize )
		{
			final int size = Math.min( blockSize, longs.size() - offset );
			final MDLongArray targetCell = new MDLongArray( new int[] { size } );
			for ( int i = 0; i < size; ++i )
				targetCell.set( iterator.next(), i );

			uint64Writer.writeMDArrayBlockWithOffset( dataset, targetCell, new long[] { offset } );
		}
	}

	/**
	 * Save a long collection into an HDF5 uint64 dataset.
	 *
	 * @param longs
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveLongCollection(
			final TLongCollection longs,
			final File file,
			final String dataset,
			final int blockSize )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveLongCollection( longs, writer, dataset, blockSize );
		writer.close();
	}

	/**
	 * Save a collection of longs into an HDF5 uint64 dataset.
	 *
	 * @param longs
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveLongCollection(
			final TLongCollection longs,
			final String filePath,
			final String dataset,
			final int blockSize )
	{
		final IHDF5Writer writer = HDF5Factory.open( filePath );
		saveLongCollection( longs, writer, dataset, blockSize );
		writer.close();
	}

	/**
	 * Load an attribute from of an HDF5 object.
	 *
	 * @param lut
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	@SuppressWarnings( "unchecked" )
	static public < T > T loadAttribute(
			final IHDF5Reader reader,
			final String object,
			final String attribute )
	{
		if ( !reader.exists( object ) )
			return null;

		if ( !reader.object().hasAttribute( object, attribute ) )
			return null;

		final HDF5DataTypeInformation attributeInfo = reader.object().getAttributeInformation( object, attribute );
		final Class< ? > type = attributeInfo.tryGetJavaType();
		if ( type.isAssignableFrom( long[].class ) )
			if ( attributeInfo.isSigned() )
				return ( T ) reader.int64().getArrayAttr( object, attribute );
			else
				return ( T ) reader.uint64().getArrayAttr( object, attribute );
		if ( type.isAssignableFrom( int[].class ) )
			if ( attributeInfo.isSigned() )
				return ( T ) reader.int32().getArrayAttr( object, attribute );
			else
				return ( T ) reader.uint32().getArrayAttr( object, attribute );
		if ( type.isAssignableFrom( short[].class ) )
			if ( attributeInfo.isSigned() )
				return ( T ) reader.int16().getArrayAttr( object, attribute );
			else
				return ( T ) reader.uint16().getArrayAttr( object, attribute );
		if ( type.isAssignableFrom( byte[].class ) )
		{
			if ( attributeInfo.isSigned() )
				return ( T ) reader.int8().getArrayAttr( object, attribute );
			else
				return ( T ) reader.uint8().getArrayAttr( object, attribute );
		}
		else if ( type.isAssignableFrom( double[].class ) )
			return ( T ) reader.float64().getArrayAttr( object, attribute );
		else if ( type.isAssignableFrom( float[].class ) )
			return ( T ) reader.float32().getArrayAttr( object, attribute );
		else if ( type.isAssignableFrom( String[].class ) )
			return ( T ) reader.string().getArrayAttr( object, attribute );
		if ( type.isAssignableFrom( long.class ) )
		{
			if ( attributeInfo.isSigned() )
				return ( T ) new Long( reader.int64().getAttr( object, attribute ) );
			else
				return ( T ) new Long( reader.uint64().getAttr( object, attribute ) );
		}
		else if ( type.isAssignableFrom( int.class ) )
		{
			if ( attributeInfo.isSigned() )
				return ( T ) new Integer( reader.int32().getAttr( object, attribute ) );
			else
				return ( T ) new Integer( reader.uint32().getAttr( object, attribute ) );
		}
		else if ( type.isAssignableFrom( short.class ) )
		{
			if ( attributeInfo.isSigned() )
				return ( T ) new Short( reader.int16().getAttr( object, attribute ) );
			else
				return ( T ) new Short( reader.uint16().getAttr( object, attribute ) );
		}
		else if ( type.isAssignableFrom( byte.class ) )
		{
			if ( attributeInfo.isSigned() )
				return ( T ) new Byte( reader.int8().getAttr( object, attribute ) );
			else
				return ( T ) new Byte( reader.uint8().getAttr( object, attribute ) );
		}
		else if ( type.isAssignableFrom( double.class ) )
			return ( T ) new Double( reader.float64().getAttr( object, attribute ) );
		else if ( type.isAssignableFrom( float.class ) )
			return ( T ) new Double( reader.float32().getAttr( object, attribute ) );
		else if ( type.isAssignableFrom( String.class ) )
			return ( T ) new String( reader.string().getAttr( object, attribute ) );

		System.err.println( "Reading attributes of type " + attributeInfo + " not yet implemented." );
		return null;
	}

	/**
	 * Load an attribute from of an HDF5 object.
	 *
	 * @param lut
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public < T > T loadAttribute(
			final File file,
			final String object,
			final String attribute )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( file );
		final T t = loadAttribute( reader, object, attribute );
		reader.close();
		return t;
	}

	static public void getAllDatasetPaths(
			final IHDF5Reader reader,
			final String object,
			final List< String > paths )
	{
		List< String > allPaths = null;

		if ( reader.object().isDataSet( object ) )
		{
			paths.add( object );
		}
		else
		{
			allPaths = reader.getGroupMembers( object );
			for ( int j = 0; j < allPaths.size(); j++ )
			{
				getAllDatasetPaths( reader, object.concat( allPaths.get( j ) ).concat( "/" ), paths );
			}
		}
	}

	/**
	 * Load an attribute from of an HDF5 object.
	 *
	 * @param lut
	 * @param file
	 * @param dataset
	 * @param cellDimensions
	 */
	static public < T > T loadAttribute(
			final String filePath,
			final String object,
			final String attribute )
	{
		final IHDF5Reader reader = HDF5Factory.openForReading( filePath );
		final T t = loadAttribute( reader, object, attribute );
		reader.close();
		return t;
	}

	/**
	 * Save a double array as a float64[] attribute of an HDF5 object.
	 *
	 * @param values
	 * @param writer
	 * @param object
	 * @param attribute
	 */
	static public void saveAttribute(
			final double[] values,
			final IHDF5Writer writer,
			final String object,
			final String attribute )
	{
		if ( !writer.exists( object ) )
			writer.object().createGroup( object );

		writer.float64().setArrayAttr( object, attribute, values );
	}

	/**
	 * Save a double array as a float64[] attribute of an HDF5 object.
	 *
	 * @param values
	 * @param file
	 * @param object
	 * @param attribute
	 */
	static public void saveAttribute(
			final double[] values,
			final File file,
			final String object,
			final String attribute )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveAttribute( values, writer, object, attribute );
		writer.close();
	}

	/**
	 * Save a double array as a float64[] attribute of an HDF5 object.
	 *
	 * @param values
	 * @param filePath
	 * @param object
	 * @param attribute
	 */
	static public void saveAttribute(
			final double[] values,
			final String filePath,
			final String object,
			final String attribute )
	{
		final IHDF5Writer writer = HDF5Factory.open( filePath );
		saveAttribute( values, writer, object, attribute );
		writer.close();
	}

	/**
	 * Save a long value as a uint64 attribute of an HDF5 object.
	 *
	 * @param value
	 * @param writer
	 * @param object
	 * @param attribute
	 */
	static public void saveUint64Attribute(
			final long value,
			final IHDF5Writer writer,
			final String object,
			final String attribute )
	{
		if ( !writer.exists( object ) )
			writer.object().createGroup( object );

		// TODO Bug in JHDF5, does not save the value most of the time when
		// using the non-deprecated method
		writer.uint64().setAttr( object, attribute, value );
		// The deprecated method no longer exists
//		writer.setLongAttribute( object, attribute, value );
//		writer.file().flush();
	}

	/**
	 * Save a long value as a uint64 attribute of an HDF5 object.
	 *
	 * @param value
	 * @param file
	 * @param object
	 * @param attribute
	 */
	static public void saveUint64Attribute(
			final long value,
			final File file,
			final String object,
			final String attribute )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		saveUint64Attribute( value, writer, object, attribute );
		writer.close();
	}

	/**
	 * Save a long value as a uint64 attribute of an HDF5 object.
	 *
	 * @param value
	 * @param filePath
	 * @param object
	 * @param attribute
	 */
	static public void saveUint64Attribute(
			final long value,
			final String filePath,
			final String object,
			final String attribute )
	{
		final IHDF5Writer writer = HDF5Factory.open( filePath );
		saveUint64Attribute( value, writer, object, attribute );
		writer.close();
	}

	static public void saveFloatArrayAttribute(
			final float[] value,
			final File file,
			final String dataset,
			final String attribute )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		writer.float32().setArrayAttr( dataset, attribute, value );
		writer.close();
	}

	static public void saveDoubleArrayAttribute(
			final double[] value,
			final File file,
			final String dataset,
			final String attribute )
	{
		final IHDF5Writer writer = HDF5Factory.open( file );
		writer.float64().setArrayAttr( dataset, attribute, value );
		writer.close();
	}

	/**
	 * Create a {@link CellLoader} for use in a lazy {@link CachedCellImg}.
	 *
	 * @param reader
	 * @param dataset
	 * @param type
	 * @param signed
	 * @return
	 */
	public static < T extends NativeType< T > > CellLoader< T > createCellLoader(
			final IHDF5Reader reader,
			final String dataset,
			final Class< ? > type,
			final boolean signed )
	{
		if ( type.isAssignableFrom( byte.class ) )
			return signed ? ( img ) -> {
				final byte[] data = reader.int8().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends GenericByteType< ? > > c = ( Cursor< ? extends GenericByteType< ? > > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().setByte( data[ i ] );
			} : ( img ) -> {
				final byte[] data = reader.uint8().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends GenericByteType< ? > > c = ( Cursor< ? extends GenericByteType< ? > > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().setByte( data[ i ] );
			};
		else if ( type.isAssignableFrom( short.class ) )
			return signed ? ( img ) -> {
				final short[] data = reader.int16().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends GenericShortType< ? > > c = ( Cursor< ? extends GenericShortType< ? > > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().setShort( data[ i ] );
			} : ( img ) -> {
				final short[] data = reader.uint16().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends GenericShortType< ? > > c = ( Cursor< ? extends GenericShortType< ? > > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().setShort( data[ i ] );
			};
		else if ( type.isAssignableFrom( int.class ) )
			return signed ? ( img ) -> {
				final int[] data = reader.int32().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends GenericIntType< ? > > c = ( Cursor< ? extends GenericIntType< ? > > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().setInt( data[ i ] );
			} : ( img ) -> {
				final int[] data = reader.uint32().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends GenericIntType< ? > > c = ( Cursor< ? extends GenericIntType< ? > > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().setInt( data[ i ] );
			};
		else if ( type.isAssignableFrom( long.class ) )
			return signed ? ( img ) -> {
				final long[] data = reader.int64().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends GenericLongType< ? > > c = ( Cursor< ? extends GenericLongType< ? > > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().setLong( data[ i ] );
			} : ( img ) -> {
				final long[] data = reader.uint64().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends GenericLongType< ? > > c = ( Cursor< ? extends GenericLongType< ? > > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().setLong( data[ i ] );
			};
		else if ( type.isAssignableFrom( float.class ) )
			return ( img ) -> {
				final float[] data = reader.float32().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends FloatType > c = ( Cursor< ? extends FloatType > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().set( data[ i ] );
			};
		else if ( type.isAssignableFrom( double.class ) )
			return ( img ) -> {
				final double[] data = reader.float64().readMDArrayBlockWithOffset(
						dataset,
						reorder( Intervals.dimensionsAsIntArray( img ) ),
						reorder( Intervals.minAsLongArray( img ) ) ).getAsFlatArray();

				@SuppressWarnings( "unchecked" )
				final Cursor< ? extends DoubleType > c = ( Cursor< ? extends DoubleType > ) img.cursor();
				for ( int i = 0; i < data.length; ++i )
					c.next().set( data[ i ] );
			};
		else
			return null;
	}

	/**
	 * Open an HDF5 dataset as a memory cached {@link LazyCellImg}, using
	 * {@link VolatileAccess} to enable wrapping as a volatile.
	 *
	 * @param n5
	 * @param dataset
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public static final < T extends NativeType< T > > RandomAccessibleInterval< T > open(
			final IHDF5Reader reader,
			final String dataset,
			final int[] blockSize ) throws IOException
	{
		final Class< ? > dataType = reader.getDataSetInformation( dataset ).getTypeInformation().tryGetJavaType();
		final boolean signed = reader.getDataSetInformation( dataset ).getTypeInformation().isSigned();

		final CellLoader< T > loader = createCellLoader( reader, dataset, dataType, signed );

		final long[] dimensions = reorder( reader.object().getDataSetInformation( dataset ).getDimensions() );

		final CellGrid grid = new CellGrid( dimensions, blockSize );

		final CachedCellImg< T, ? > img;
		final T type;
		final Cache< Long, Cell< ? > > cache;

		if ( dataType.isAssignableFrom( byte.class ) )
		{
			if ( signed )
			{
				type = ( T ) new ByteType();
				cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< ByteArray > >()
						.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
				img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( BYTE, VOLATILE ) );
			}
			else
			{
				type = ( T ) new UnsignedByteType();
				cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< ByteArray > >()
						.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
				img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( BYTE, VOLATILE ) );
			}
		}
		else if ( dataType.isAssignableFrom( short.class ) )
		{
			if ( signed )
			{
				type = ( T ) new ShortType();
				cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< ShortArray > >()
						.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
				img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( SHORT, VOLATILE ) );
			}
			else
			{
				type = ( T ) new UnsignedShortType();
				cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< ShortArray > >()
						.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
				img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( SHORT, VOLATILE ) );
			}
		}
		else if ( dataType.isAssignableFrom( int.class ) )
		{
			if ( signed )
			{
				type = ( T ) new IntType();
				cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< IntArray > >()
						.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
				img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( INT, VOLATILE ) );
			}
			else
			{
				type = ( T ) new UnsignedIntType();
				cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< IntArray > >()
						.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
				img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( INT, VOLATILE ) );
			}
		}
		else if ( dataType.isAssignableFrom( long.class ) )
		{
			if ( signed )
			{
				type = ( T ) new LongType();
				cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< LongArray > >()
						.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
				img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( LONG, VOLATILE ) );
			}
			else
			{
				type = ( T ) new UnsignedLongType();
				cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< LongArray > >()
						.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
				img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( LONG, VOLATILE ) );
			}
		}
		else if ( dataType.isAssignableFrom( float.class ) )
		{
			type = ( T ) new FloatType();
			cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< FloatArray > >()
					.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
			img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( FLOAT, VOLATILE ) );
		}
		else if ( dataType.isAssignableFrom( double.class ) )
		{
			type = ( T ) new DoubleType();
			cache = ( Cache ) new SoftRefLoaderCache< Long, Cell< DoubleArray > >()
					.withLoader( LoadedCellCacheLoader.get( grid, loader, type, VOLATILE ) );
			img = new CachedCellImg( grid, type, cache, ArrayDataAccessFactory.get( DOUBLE, VOLATILE ) );
		}
		else
			img = null;

		return img;
	}

	/**
	 * Open an HDF5 dataset as a memory cached {@link LazyCellImg}, using
	 * {@link VolatileAccess} to enable wrapping as a volatile.
	 *
	 * @param n5
	 * @param dataset
	 * @return
	 * @throws IOException
	 */
	public static final < T extends NativeType< T > > RandomAccessibleInterval< T > open(
			final IHDF5Reader reader,
			final String dataset ) throws IOException
	{
		final HDF5DataSetInformation datasetInfo = reader.object().getDataSetInformation( dataset );
		final int[] blockSize;
		if ( datasetInfo.getStorageLayout() == HDF5StorageLayout.CHUNKED )
			blockSize = reorder( datasetInfo.tryGetChunkSizes() );
		else
		{
			blockSize = new int[ datasetInfo.getRank() ];
			Arrays.fill( blockSize, 32 );
		}

		return open( reader, dataset, blockSize );
	}
}
