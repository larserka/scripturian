/**
 * Copyright 2009-2010 Three Crickets LLC.
 * <p>
 * The contents of this file are subject to the terms of the LGPL version 3.0:
 * http://www.opensource.org/licenses/lgpl-3.0.html
 * <p>
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly from Three Crickets
 * at http://threecrickets.com/
 */

package com.threecrickets.scripturian.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.script.Compilable;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;

import com.threecrickets.scripturian.Executable;
import com.threecrickets.scripturian.ExecutionContext;
import com.threecrickets.scripturian.LanguageAdapter;
import com.threecrickets.scripturian.Scriptlet;
import com.threecrickets.scripturian.exception.ExecutableInitializationException;
import com.threecrickets.scripturian.exception.ExecutionException;
import com.threecrickets.scripturian.exception.LanguageInitializationException;

/**
 * @author Tal Liron
 */
public abstract class Jsr223LanguageAdapter implements LanguageAdapter
{
	//
	// Constants
	//

	public static final String JSR223_SCRIPT_ENGINE_MANAGER = "jsr223.scriptEngineManager";

	public static final String JSR223_SCRIPT_ENGINE_NAME = "jsr223.scriptEngineName";

	public static final String JSR223_SCRIPT_CONTEXT = "jsr223.scriptContext";

	public static final String JSR223_SCRIPT_ENGINES = "jsr223.scriptEngines";

	//
	// Static operations
	//

	public static ScriptContext getScriptContext( ExecutionContext executionContext )
	{
		ScriptContext scriptContext = (ScriptContext) executionContext.getAttributes().get( JSR223_SCRIPT_CONTEXT );

		if( scriptContext == null )
		{
			scriptContext = new SimpleScriptContext();
			executionContext.getAttributes().put( JSR223_SCRIPT_CONTEXT, scriptContext );
			scriptContext.setBindings( new SimpleBindings(), ScriptContext.ENGINE_SCOPE );
			scriptContext.setBindings( new SimpleBindings(), ScriptContext.GLOBAL_SCOPE );
		}

		scriptContext.setWriter( executionContext.getWriter() );
		scriptContext.setErrorWriter( executionContext.getErrorWriter() );
		for( Map.Entry<String, Object> entry : executionContext.getExposedVariables().entrySet() )
			scriptContext.setAttribute( entry.getKey(), entry.getValue(), ScriptContext.ENGINE_SCOPE );

		return scriptContext;
	}

	public static ScriptEngineManager getScriptEngineManager( ExecutionContext executionContext )
	{
		ScriptEngineManager scriptEngineManager = (ScriptEngineManager) executionContext.getManager().getAttributes().get( JSR223_SCRIPT_ENGINE_MANAGER );
		if( scriptEngineManager == null )
		{
			scriptEngineManager = new ScriptEngineManager();
			ScriptEngineManager existing = (ScriptEngineManager) executionContext.getManager().getAttributes().putIfAbsent( JSR223_SCRIPT_ENGINE_MANAGER, scriptEngineManager );
			if( existing != null )
				scriptEngineManager = existing;
		}
		return scriptEngineManager;
	}

	/**
	 * A cached script engine. All script engines will use the same
	 * {@link ScriptContext}.
	 * 
	 * @param scriptEngineName
	 *        The script engine name
	 * @param document
	 *        The document for debugging
	 * @param executionContext
	 *        The document context
	 * @return The cached script engine
	 * @throws ExecutableInitializationException
	 */
	@SuppressWarnings("unchecked")
	public static ScriptEngine getScriptEngine( String scriptEngineName, Executable document, ExecutionContext executionContext ) throws ExecutableInitializationException
	{
		Map<String, ScriptEngine> scriptEngines = (Map<String, ScriptEngine>) executionContext.getAttributes().get( JSR223_SCRIPT_ENGINES );
		if( scriptEngines == null )
		{
			scriptEngines = new HashMap<String, ScriptEngine>();
			executionContext.getAttributes().put( JSR223_SCRIPT_ENGINES, scriptEngines );
		}

		ScriptEngine scriptEngine = scriptEngines.get( scriptEngineName );

		if( scriptEngine == null )
		{
			ScriptEngineManager scriptEngineManager = getScriptEngineManager( executionContext );
			scriptEngine = scriptEngineManager.getEngineByName( scriptEngineName );
			if( scriptEngine == null )
				throw new ExecutableInitializationException( document.getName(), "Unsupported script engine: " + scriptEngineName );

			// (Note that some script engines do not even
			// provide a default context -- Jepp, for example -- so
			// it's generally a good idea to explicitly set one)
			scriptEngine.setContext( getScriptContext( executionContext ) );

			scriptEngines.put( scriptEngineName, scriptEngine );
		}

		return scriptEngine;
	}

	//
	// Construction
	//

	public Jsr223LanguageAdapter() throws LanguageInitializationException
	{
		ScriptEngines scriptEngineNames = getClass().getAnnotation( ScriptEngines.class );
		if( scriptEngineNames == null )
			throw new LanguageInitializationException( getClass(), getClass().getName() + " does not have a ScriptEngines annotation" );

		String scriptEngineName = scriptEngineNames.value()[0];
		ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
		scriptEngine = scriptEngineManager.getEngineByName( scriptEngineName );
		if( scriptEngine == null )
			throw new LanguageInitializationException( getClass(), getClass().getName() + " could not load ScriptEngine " + scriptEngineName );

		ScriptEngineFactory factory = scriptEngine.getFactory();

		attributes.put( NAME, factory.getEngineName() );
		attributes.put( VERSION, factory.getEngineVersion() );
		attributes.put( LANGUAGE_NAME, factory.getLanguageName() );
		attributes.put( LANGUAGE_VERSION, factory.getLanguageVersion() );
		attributes.put( EXTENSIONS, new ArrayList<String>( factory.getExtensions() ) );
		attributes.put( DEFAULT_EXTENSION, factory.getExtensions().get( 0 ) );
		attributes.put( TAGS, new ArrayList<String>( factory.getNames() ) );
		attributes.put( DEFAULT_TAG, factory.getNames().get( 0 ) );

		attributes.put( JSR223_SCRIPT_ENGINE_NAME, scriptEngineName );
	}

	//
	// Attributes
	//

	public ScriptEngine getScriptEngine( Executable document, ExecutionContext executionContext ) throws ExecutableInitializationException
	{
		String scriptEngineName = (String) attributes.get( JSR223_SCRIPT_ENGINE_NAME );
		return getScriptEngine( scriptEngineName, document, executionContext );
	}

	public ScriptEngine getStaticScriptEngine()
	{
		return scriptEngine;
	}

	/**
	 * Though some scripting engines support {@link Compilable}, their
	 * implementation is broken for our purposes. This allows us to bypass
	 * compilation.
	 * 
	 * @return True if compilation is allowed
	 */
	public boolean isCompilable()
	{
		return true;
	}

	/**
	 * Some languages support their own printing facilities, while others don't.
	 * Returning true here will delegate print handling to {@link Executable}.
	 * 
	 * @return True if should print on eval
	 */
	public boolean isPrintOnEval()
	{
		return false;
	}

	public void beforeCall( ScriptEngine scriptEngine, ExecutionContext executionContext )
	{
	}

	public void afterCall( ScriptEngine scriptEngine, ExecutionContext executionContext )
	{
	}

	/**
	 * The header is inserted at the beginning of every script. It is useful for
	 * appropriately setting up the script's environment.
	 * 
	 * @param document
	 *        The composite script instance
	 * @param scriptEngine
	 *        The script engine
	 * @return The header or null
	 */
	public String getScriptletHeader( Executable document, ScriptEngine scriptEngine )
	{
		return null;
	}

	/**
	 * The footer is appended to the end of every script. It is useful for
	 * cleaning up resources created in the header.
	 * 
	 * @param document
	 *        The composite script instance
	 * @param scriptEngine
	 *        The script engine
	 * @return The footer or null
	 * @see #getScriptletHeader(Executable, ScriptEngine)
	 */
	public String getScriptletFooter( Executable document, ScriptEngine scriptEngine )
	{
		return null;
	}

	/**
	 * Turns text into a command or series of commands to print the text to
	 * standard output. Note that the text is of arbitrary length, can span
	 * multiple lines, and include arbitrary characters. The parsing helper
	 * makes sure to escape special characters, partition long text, etc.
	 * 
	 * @param document
	 *        The composite script instance
	 * @param scriptEngine
	 *        The script engine
	 * @param text
	 *        The content
	 * @return A command or series of commands to print the content
	 */
	public String getTextAsProgram( Executable document, ScriptEngine scriptEngine, String text )
	{
		return null;
	}

	/**
	 * Turns an expression into a command or series of commands to print the
	 * evaluation of the expression to standard output. In most cases this
	 * simply involves wrapping the expression in something like a print
	 * command.
	 * 
	 * @param document
	 *        The composite script instance
	 * @param scriptEngine
	 *        The script engine
	 * @param text
	 *        The content
	 * @return A command or series of commands to print the expression
	 */
	public String getExpressionAsProgram( Executable document, ScriptEngine scriptEngine, String text )
	{
		return null;
	}

	/**
	 * Turns an expression into a command or series of commands to include the
	 * script named for the result of the evaluation of the expression. Note
	 * that this requires the script to have access to a global method named
	 * <code>documentnt.container.includeDocument</code>.
	 * 
	 * @param document
	 *        The composite script instance
	 * @param scriptEngine
	 *        The script engine
	 * @param text
	 *        The content
	 * @return A command or series of commands to include the script named for
	 *         the expression
	 * @see Executable#getExposedExecutableName()
	 */
	public String getExpressionAsInclude( Executable document, ScriptEngine scriptEngine, String text )
	{
		return null;
	}

	/**
	 * Creates a command or series of commands to invoke an entry point
	 * (function, method, closure, etc.) in the script. Note that for engines
	 * that implement {@link Invocable} you must return null.
	 * 
	 * @param document
	 *        The composite script instance
	 * @param scriptEngine
	 *        The script engine
	 * @param content
	 *        The content
	 * @return A command or series of commands to call the entry point, or null
	 *         to signify that {@link Invocable} should be used
	 */
	public String getInvocationAsProgram( Executable document, ScriptEngine scriptEngine, String content )
	{
		return null;
	}

	//
	// LanguageAdapter
	//

	public ConcurrentMap<String, Object> getAttributes()
	{
		return attributes;
	}

	public boolean isThreadSafe()
	{
		return true;
	}

	public Lock getLock()
	{
		return lock;
	}

	public String getCodeForLiteralOutput( String literal, Executable document ) throws ExecutableInitializationException
	{
		return getTextAsProgram( document, scriptEngine, literal );
	}

	public String getCodeForExpressionOutput( String expression, Executable document ) throws ExecutableInitializationException
	{
		return getExpressionAsProgram( document, scriptEngine, expression );
	}

	public String getCodeForExpressionInclude( String expression, Executable document ) throws ExecutableInitializationException
	{
		return getExpressionAsInclude( document, scriptEngine, expression );
	}

	public Throwable getCauseOrExecutionException( String documentName, Throwable throwable )
	{
		return null;
	}

	public Scriptlet createScriptlet( String code, Executable document ) throws ExecutableInitializationException
	{
		// Add header
		String header = getScriptletHeader( document, scriptEngine );
		if( header != null )
			code = header + code;

		// Add footer
		String footer = getScriptletFooter( document, scriptEngine );
		if( footer != null )
			code += footer;

		return new Jsr223Scriptlet( code, this, document );
	}

	public Object invoke( String method, Executable document, ExecutionContext executionContext ) throws NoSuchMethodException, ExecutableInitializationException, ExecutionException
	{
		ScriptEngine scriptEngine = getScriptEngine( document, executionContext );
		scriptEngine.setContext( getScriptContext( executionContext ) );

		String code = getInvocationAsProgram( document, scriptEngine, method );
		if( code == null )
		{
			if( scriptEngine instanceof Invocable )
			{
				try
				{
					beforeCall( scriptEngine, executionContext );
					return ( (Invocable) scriptEngine ).invokeFunction( method );
				}
				catch( ScriptException x )
				{
					throw ExecutionException.create( document.getName(), executionContext.getManager(), x );
				}
				catch( NoSuchMethodException x )
				{
					throw x;
				}
				catch( Exception x )
				{
					// Some script engines (notably Quercus) throw their
					// own special exceptions
					throw ExecutionException.create( document.getName(), executionContext.getManager(), x );
				}
				finally
				{
					afterCall( scriptEngine, executionContext );
				}
			}
			else
			{
				String scriptEngineName = (String) attributes.get( JSR223_SCRIPT_ENGINE_NAME );
				throw new ExecutionException( document.getName(), "Script engine " + scriptEngineName + " does not support invocations" );
			}
		}
		else
		{
			try
			{
				beforeCall( scriptEngine, executionContext );
				return scriptEngine.eval( code );
			}
			catch( ScriptException x )
			{
				throw ExecutionException.create( document.getName(), executionContext.getManager(), x );
			}
			catch( Exception x )
			{
				// Some script engines (notably Quercus) throw their
				// own special exceptions
				throw ExecutionException.create( document.getName(), executionContext.getManager(), x );
			}
			finally
			{
				afterCall( scriptEngine, executionContext );
			}
		}
	}

	// //////////////////////////////////////////////////////////////////////////
	// Private

	private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<String, Object>();

	private final Lock lock = new ReentrantLock();

	private final ScriptEngine scriptEngine;
}
