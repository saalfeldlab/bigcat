# run first (in bigcat root directory):
# export CLASSPATH=/path/to/bigcat-0.0.1-SNAPSHOT.jar:$(mvn dependency:build-classpath | grep  '^[^[]')

import imglyb

from jnius import autoclass

import threading

import time

if __name__ == "__main__":

	BigDataViewerJan = autoclass('bdv.bigcat.BigCatViewerJan')
	Parameters = autoclass('bdv.bigcat.BigCatViewerJan$Parameters')
	BdvWindowClosedCheck = autoclass('net.imglib2.python.BdvWindowClosedCheck')
	H5Spec = autoclass('bdv.bigcat.BigCatViewerJan$H5Specification')
	DataType = autoclass( 'bdv.bigcat.BigCatViewerJan$DatasetSpecification$DataType' )

	# set up arguments
	# use hard coded arguments for now

	rawFile = '/groups/saalfeld/home/hanslovskyp/from_funkej/phil/sample_B.augmented.0.hdf'
	rawDataSet = 'volumes/raw'
	groundTruthFile = rawFile
	groundTruthDataset = 'volumes/labels/neuron_ids_notransparency'
	predictionFile = '/groups/saalfeld/home/hanslovskyp/from_funkej/phil/sample_B_median_aff_cf_hq_dq_au00.87.hdf'
	predictionDataset = 'main'
	
	offset = [ 600, 424, 424 ][::-1]
	resolution = [ 40, 4, 4 ][::-1]


	

	cellSize = ( 145, 53, 5 )

	raw  = H5Spec().cellSize( cellSize ).path( rawFile ).dataset( rawDataSet ).resolution( resolution ).offset( None ).dataType( DataType.RAW )
	gt1  = H5Spec().cellSize( cellSize ).path( groundTruthFile ).dataset( groundTruthDataset ).resolution( resolution ).offset( None ).dataType( DataType.LABEL )
	gt2  = H5Spec().cellSize( cellSize ).path( groundTruthFile ).dataset( groundTruthDataset ).resolution( resolution ).offset( None ).dataType( DataType.LABEL )
	pred = H5Spec().cellSize( cellSize ).path( predictionFile ).dataset( predictionDataset ).resolution( resolution ).offset( offset ).dataType( DataType.LABEL )

	
	bigCat = BigDataViewerJan.run( raw, gt1, gt2, pred )
	check = BdvWindowClosedCheck()
	bigCat.getViewerFrame().addWindowListener( check )

	# bigCat.highlight( gt1, highlights )

	def check_window():
		while check.isOpen():
			time.sleep( 0.1 )

	check_thread = threading.Thread( target = check_window )
	check_thread.start()

	
	
	
