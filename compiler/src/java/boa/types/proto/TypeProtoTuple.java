package boa.types.proto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import boa.types.BoaProtoTuple;
import boa.types.BoaString;
import boa.types.BoaType;
import boa.types.proto.enums.TypeKindProtoMap;

/**
 * A {@link TypeProtoTuple}.
 * 
 * @author rdyer
 */
public class TypeProtoTuple extends BoaProtoTuple {
	private final static List<BoaType> members = new ArrayList<BoaType>();
	private final static Map<String, Integer> names = new HashMap<String, Integer>();

	static {
		names.put("name", 0);
		members.add(new BoaString());

		names.put("kind", 1);
		members.add(new TypeKindProtoMap());

		names.put("id", 3);
		members.add(new BoaString());
	}

	/**
	 * Construct a {@link TypeProtoTuple}.
	 */
	public TypeProtoTuple() {
		super(members, names);
	}

	@Override
	public String toJavaType() {
		return "boa.types.Ast.Type";
	}
}
