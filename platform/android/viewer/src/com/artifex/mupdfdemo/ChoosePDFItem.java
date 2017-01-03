package com.artifex.mupdfdemo;

public class ChoosePDFItem {
	enum Type {
		PARENT, DIR, DOC
	}

	final public Type type;
	final public String name;
	final public String fullPath;

	public ChoosePDFItem (Type t, String n, String path) {
		type = t;
		name = n;
		fullPath = path;
	}
}
