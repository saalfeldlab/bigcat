package bdv.bigcat.viewer.meshes;

import java.util.Arrays;

import bdv.bigcat.viewer.state.FragmentSegmentAssignment;
import bdv.bigcat.viewer.util.ui.NumericSliderWithField;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

public class MeshInfoNode
{

	private final MeshInfo meshInfo;

	public MeshInfoNode( final MeshInfo meshInfo )
	{
		super();
		this.meshInfo = meshInfo;
	}

	public MeshInfoNode( final long segmentId, final FragmentSegmentAssignment assignment, final MeshManager meshManager, final int numScaleLevels )
	{
		this( new MeshInfo( segmentId, assignment, meshManager, numScaleLevels ) );
	}

	public Node getNode()
	{
		final VBox vbox = new VBox();

		final long[] fragments = meshInfo.assignment().getFragments( meshInfo.segmentId() ).toArray();

		final GridPane contents = new GridPane();

		int row = 0;
		contents.add( new Label( "Ids:" ), 0, row );
		contents.add( new Label( Arrays.toString( fragments ) ), 1, row );
		++row;

		final NumericSliderWithField scaleSlider = new NumericSliderWithField( 0, meshInfo.numScaleLevels() - 1, meshInfo.scaleLevelProperty().get() );
		contents.add( new Label( "Scale" ), 0, row );
		contents.add( scaleSlider.slider(), 1, row );
		contents.add( scaleSlider.textField(), 2, row );
		scaleSlider.slider().setShowTickLabels( true );
		scaleSlider.slider().setTooltip( new Tooltip( "Render meshes at scale level" ) );
		scaleSlider.slider().valueProperty().bindBidirectional( meshInfo.scaleLevelProperty() );
		++row;

		final NumericSliderWithField simplificationSlider = new NumericSliderWithField( 0, 10, meshInfo.simplificationIterationsProperty().get() );
		contents.add( new Label( "Iterations" ), 0, row );
		contents.add( simplificationSlider.slider(), 1, row );
		contents.add( simplificationSlider.textField(), 2, row );
		simplificationSlider.slider().setShowTickLabels( true );
		simplificationSlider.slider().setTooltip( new Tooltip( "Simplify meshes n times." ) );
		simplificationSlider.slider().valueProperty().bindBidirectional( meshInfo.simplificationIterationsProperty() );
		++row;

		vbox.getChildren().add( contents );

		return vbox;
	}

}
