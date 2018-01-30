package bdv.bigcat.viewer.atlas.source;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.bigcat.viewer.state.SelectedIds;
import gnu.trove.set.hash.TLongHashSet;
import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

public class SelectedIdsTextField
{

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	private final TextField field = new TextField();

	public SelectedIdsTextField( final SelectedIds selection )
	{
		super();

		field.setPromptText( "Selected ids" );
		field.setTooltip( new Tooltip( "Selected fragment ids" ) );

		selection.addListener( () -> {
			final long[] ids = selection.getActiveIds();
			final String text = String.join( ",", Arrays.stream( ids ).mapToObj( Long::toString ).toArray( String[]::new ) );
			LOG.debug( "Setting text to {}", text );
			field.setText( text );
		} );

		field.textProperty().addListener( ( obs, oldv, newv ) -> {
			if ( newv != null )
				if ( isLegalString( newv ) )
				{
					final long[] textFieldSelection = toLongArray( newv );
					final long[] currentSelection = selection.getActiveIds();
					Arrays.sort( currentSelection );
					Arrays.sort( textFieldSelection );
					if ( !Arrays.equals( textFieldSelection, currentSelection ) )
					{
						LOG.debug(
								"Updating selected ids from user input: {}/{} (old/new)",
								Arrays.toString( currentSelection ), Arrays.toString( textFieldSelection ) );
						Platform.runLater( () -> {
							final int caretPosition = field.getCaretPosition();
							selection.activate( textFieldSelection );
							field.positionCaret( caretPosition );
						} );
					}
				}
				else
					field.setText( oldv );
		} );

	}

	public TextField textField()
	{
		return this.field;
	}

	private static boolean isLegalString( final String text )
	{
		return text.length() == 0 || Pattern.matches( "^[0-9]+(\\s*,[0-9]+)*\\s*,?$", text );
	}

	private static long[] toLongArray( final String text )
	{
		final String stripped = StringUtils.strip( text, "," );
		final String[] split = stripped.replaceAll( "\\s+", "" ).split( "," );
		final long[] textFieldSelection = text.length() == 0 || split.length == 0 ? new long[ 0 ] : Arrays.stream( split ).mapToLong( Long::parseLong ).toArray();
		final long[] uniqueSelection = new TLongHashSet( textFieldSelection ).toArray();
		return uniqueSelection;
	}

}
