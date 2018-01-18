package bdv.bigcat.viewer.atlas;

import java.io.DataInputStream;
import java.io.FileInputStream;

import bdv.bigcat.viewer.util.InvokeOnJavaFXApplicationThread;
import bdv.bigcat.viewer.viewer3d.Viewer3DFX;
import bdv.bigcat.viewer.viewer3d.marchingCubes.MarchingCubes;
import bdv.util.volatiles.SharedQueue;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import javafx.stage.Stage;

public class MeshExample extends Application
{

	@Override
	public void start( final Stage primaryStage ) throws Exception
	{
		final SharedQueue sharedQueue = new SharedQueue( 1, 20 );
		final Atlas atlas = new Atlas( sharedQueue );
		atlas.start( primaryStage, "mesh example" );

		Platform.setImplicitExit( true );

		final TriangleMesh mesh = new TriangleMesh();
		final String path = "src/main/resources/neuron-mesh";
		final double[] com = new double[ 3 ];
		try (final FileInputStream fis = new FileInputStream( path ))
		{
			try (final DataInputStream dis = new DataInputStream( fis ))
			{
				final int numEntries = dis.readInt();
				System.out.println( "num entries: " + numEntries );
				final float[] vertices = new float[ numEntries ];
				for ( int i = 0; i < numEntries; i += 3 )
				{
					final float v1 = dis.readFloat();
					final float v2 = dis.readFloat();
					final float v3 = dis.readFloat();
					vertices[ i + 0 ] = v1;
					vertices[ i + 1 ] = v2;
					vertices[ i + 2 ] = v3;
					com[ 0 ] += v1;
					com[ 1 ] += v2;
					com[ 2 ] += v3;
				}

				final int numEntriesBy3 = numEntries / 3;

				for ( int d = 0; d < 3; ++d )
					com[ d ] /= numEntriesBy3;

				for ( int i = 0; i < numEntriesBy3; i += 3 )
				{
					vertices[ i + 0 ] -= com[ 0 ];
					vertices[ i + 1 ] -= com[ 1 ];
					vertices[ i + 2 ] -= com[ 2 ];
					vertices[ i + 0 ] /= 100;
					vertices[ i + 1 ] /= 100;
					vertices[ i + 2 ] /= 100;

				}

				final int[] faceIndices = new int[ vertices.length ];
				for ( int i = 0, k = 0; i < vertices.length; i += 3, ++k )
				{
					faceIndices[ i + 0 ] = k;
					faceIndices[ i + 1 ] = k;
					faceIndices[ i + 2 ] = 0;
				}

				mesh.getPoints().addAll( vertices );
//				System.out.println( Arrays.toString( vertices ) );

				final float[] normals = new float[ vertices.length ];
//				MarchingCubes.surfaceNormals( vertices, normals );
				MarchingCubes.averagedSurfaceNormals( vertices, normals );
				for ( int i = 0; i < normals.length; ++i )
					normals[ i ] *= -1;
				mesh.getNormals().addAll( normals );
				mesh.setVertexFormat( VertexFormat.POINT_NORMAL_TEXCOORD );
				mesh.getTexCoords().addAll( 0.0f, 0.0f );
				mesh.getFaces().addAll( faceIndices );

			}
		}

		final MeshView mv = new MeshView( mesh );
		mv.setCullFace( CullFace.NONE );
		final PhongMaterial material = new PhongMaterial( Color.WHITE );
		mv.setOpacity( 1.0 );
		mv.setMaterial( material );
		mv.setTranslateZ( 1 );

		final Viewer3DFX v3d = atlas.renderView();
		final Group mg = v3d.meshesGroup();
		final Box sphere = new Box( 1, 1, 1 );
		sphere.setTranslateZ( 1 );

		InvokeOnJavaFXApplicationThread.invoke( () -> {
			mg.getChildren().add( mv );
			mg.getChildren().add( sphere );
		} );

	}

	public static void main( final String[] args )
	{
		launch( args );
	}

}
