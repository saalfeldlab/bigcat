package org.janelia.saalfeldlab.paintera.ui.source.mesh;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.fx.ui.NumericSliderWithField;
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread;
import org.janelia.saalfeldlab.paintera.SourceState;
import org.janelia.saalfeldlab.paintera.meshes.MeshInfo;
import org.janelia.saalfeldlab.paintera.meshes.MeshInfos;
import org.janelia.saalfeldlab.paintera.meshes.MeshManager;
import org.janelia.saalfeldlab.paintera.ui.BindUnbindAndNodeSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import deprecated.Maps;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

public class MeshPane implements BindUnbindAndNodeSupplier, ListChangeListener< MeshInfo >
{

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	private final MeshManager manager;

	private final MeshInfos meshInfos;

	private final int numScaleLevels;

	private final NumericSliderWithField scaleSlider;

	private final NumericSliderWithField simplificationSlider;

	final ObservableMap< MeshInfo, MeshInfoNode > infoNodesCache = FXCollections.observableHashMap();

	final ObservableList< MeshInfoNode > infoNodes = FXCollections.observableArrayList();

	private final VBox managerSettingsPane;

	private final VBox meshesBox = new VBox();

	private final TitledPane meshesPane = new TitledPane( "Meshes", meshesBox );
	{
//		meshesPane.setMinWidth( 50 );
		meshesPane.setMaxWidth( Double.MAX_VALUE );
	}

	private boolean isBound = false;

	public MeshPane( final MeshManager manager, final MeshInfos meshInfos, final int numScaleLevels )
	{
		super();
		this.manager = manager;
		this.meshInfos = meshInfos;
		this.numScaleLevels = numScaleLevels;

		scaleSlider = new NumericSliderWithField( 0, this.numScaleLevels - 1, manager.scaleLevelProperty().get() );
		simplificationSlider = new NumericSliderWithField( 0, 10, 0 );

		managerSettingsPane = new VBox( new Label( "Defaults" ), setupManagerSliderGrid(), meshesPane );

		this.meshInfos.readOnlyInfos().addListener( this );

	}

	@Override
	public Node get()
	{
		return managerSettingsPane;
	}

	@Override
	public void bind()
	{
		isBound = true;
		this.meshInfos.readOnlyInfos().addListener( this );
		scaleSlider.slider().valueProperty().bindBidirectional( manager.scaleLevelProperty() );
		simplificationSlider.slider().valueProperty().bindBidirectional( manager.meshSimplificationIterationsProperty() );
		new ArrayList<>( this.infoNodes ).forEach( MeshInfoNode::bind );
	}

	@Override
	public void unbind()
	{
		isBound = false;
		this.meshInfos.readOnlyInfos().removeListener( this );
		scaleSlider.slider().valueProperty().unbindBidirectional( manager.scaleLevelProperty() );
		simplificationSlider.slider().valueProperty().unbindBidirectional( manager.meshSimplificationIterationsProperty() );
		new ArrayList<>( this.infoNodes ).forEach( MeshInfoNode::unbind );
	}

	@Override
	public void onChanged( final Change< ? extends MeshInfo > change )
	{
		while ( change.next() )
			if ( change.wasRemoved() )
				change.getRemoved().forEach( info -> Optional.ofNullable( infoNodesCache.remove( info ) ).ifPresent( MeshInfoNode::unbind ) );
		populateInfoNodes( this.meshInfos.readOnlyInfos() );
	}

	private void populateInfoNodes( final List< MeshInfo > infos )
	{
		final List< MeshInfoNode > infoNodes = new ArrayList<>( infos ).stream().map( this::fromMeshInfo ).collect( Collectors.toList() );
		LOG.debug( "Setting info nodes: {}: ", infoNodes );
		this.infoNodes.setAll( infoNodes );
		final Button exportMeshButton = new Button( "Export all" );
		exportMeshButton.setOnAction( event -> {
			MeshExporterDialog exportDialog = new MeshExporterDialog( meshInfos );
			final Optional< ExportResult > result = exportDialog.showAndWait();
			if ( result.isPresent() )
			{
				ExportResult parameters = result.get();

				final SourceState< ?, ? >[] states = new SourceState< ?, ? >[ meshInfos.readOnlyInfos().size() ];
				for ( int i = 0; i < meshInfos.readOnlyInfos().size(); i++ )
					states[ i ] = meshInfos.readOnlyInfos().get( i ).state();
				parameters.getMeshExporter().exportMesh( states, parameters.getSegmentId(), parameters.getScale(), parameters.getFilePaths() );
			}
		} );
		InvokeOnJavaFXApplicationThread.invoke( () -> {
			this.meshesBox.getChildren().setAll( infoNodes.stream().map( MeshInfoNode::get ).collect( Collectors.toList() ) );
			this.meshesBox.getChildren().add( exportMeshButton );
		} );
	}

	private Node setupManagerSliderGrid()
	{

		final GridPane contents = new GridPane();

		int row = 0;
		contents.add( new Label( "Scale" ), 0, row );
		contents.add( scaleSlider.slider(), 1, row );
		contents.add( scaleSlider.textField(), 2, row );
		scaleSlider.slider().setShowTickLabels( true );
		scaleSlider.slider().setTooltip( new Tooltip( "Default for scale level." ) );
		++row;

		contents.add( new Label( "Iterations" ), 0, row );
		contents.add( simplificationSlider.slider(), 1, row );
		contents.add( simplificationSlider.textField(), 2, row );
		simplificationSlider.slider().setShowTickLabels( true );
		simplificationSlider.slider().setTooltip( new Tooltip( "Default for simplification iterations." ) );
		++row;

		return contents;
	}

	private MeshInfoNode fromMeshInfo( final MeshInfo info )
	{
		final MeshInfoNode node = Maps.getOrDefault( infoNodesCache, info, new MeshInfoNode( info ) );
		if ( this.isBound )
			node.bind();
		return node;
	}

}