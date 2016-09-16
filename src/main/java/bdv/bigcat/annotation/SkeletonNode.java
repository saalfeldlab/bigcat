package bdv.bigcat.annotation;

import java.util.LinkedList;
import java.util.List;

import net.imglib2.RealPoint;

public class SkeletonNode extends Annotation {

	public SkeletonNode(long id, RealPoint pos, String comment) {
		super(id, pos, comment);
	}

	public SkeletonNode getParent() {
		return parent;
	}
	
	public List<SkeletonNode> getChildren() {
		return children;
	}

	public void setParent(SkeletonNode parent) {
		this.parent = parent;
	}

	public void addChild(SkeletonNode child) {
		this.children.add(child);
		
	}

	@Override
	public void accept(AnnotationVisitor visitor) {
		super.accept(visitor);
		visitor.visit(this);
	}
	
	@Override
	public String toString() {
		return this.getClass().getName();
	}
	
	private SkeletonNode parent = null;
	private List<SkeletonNode> children = new LinkedList<SkeletonNode>();
}