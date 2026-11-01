package org.documentknowledge.knowledgebase.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.documentknowledge.knowledgebase.application.KnowledgeDiagnostics.*;

class KnowledgeDiagnosticsTest {
    static final String PRIVATE="synthetic-private-token tenant document SQL https://private.invalid/\nforged-log";
    @Test void hostileMessagesNeverEnterClosedEventsOrRendering(){
        var problems=new Throwable[]{new KbFailure(503,PRIVATE),new IOException(PRIVATE),new InterruptedException(PRIVATE),new TimeoutException(PRIVATE),new IllegalStateException(PRIVATE),new RuntimeException(PRIVATE,new TimeoutException(PRIVATE))};
        var expected=new Failure[]{Failure.UNAVAILABLE,Failure.IO,Failure.CANCELLED,Failure.TIMEOUT,Failure.UNEXPECTED,Failure.TIMEOUT};
        for(Stage stage:Stage.values())for(int i=0;i<problems.length;i++){
            Event event=classify(stage,problems[i]);assertEquals(expected[i],event.failure());assertEquals(stage,event.stage());assertEquals(SqlState.NONE,event.sqlState());
            assertTrue(event.line().matches("kb_failure stage=[A-Z_]+ category=[A-Z_]+ sql_state=[A-Z0-9_]+"));assertFalse(event.line().contains(PRIVATE));
        }
        for(int status:new int[]{400,401,403,404,409,422,429})assertNull(classify(Stage.HTTP_WORK,new KbFailure(status,PRIVATE)));
    }
    @Test void onlyExactAllowedSqlStatesSurviveClassification(){
        String[] states={"08000","08001","08003","08006","08007","08P01","23503","23505","23514","25006","25P02","40001","40P01","42501","53300","53400","57014","57P01","57P02","57P03","58000","XX000"};
        for(String state:states)assertNotEquals(SqlState.OTHER,sqlState(state));
        assertEquals(SqlState.REFERENCE_23503,sqlState("23503"));assertEquals(SqlState.DUPLICATE_23505,sqlState("23505"));assertEquals(SqlState.CHECK_23514,sqlState("23514"));
        for(String state:new String[]{null,"",PRIVATE,"23514\n"+PRIVATE," 23514","235140","23514 ","xx000"})assertEquals(SqlState.OTHER,sqlState(state));
    }
    @Test void diagnosticSinkFailureCannotReplaceOrExposeOriginalOutcome(){
        var events=new ArrayList<Event>();Event event=classify(Stage.FACTORY,new IOException(PRIVATE));emit(event,events::add);assertEquals(1,events.size());emit(null,events::add);assertEquals(1,events.size());
        assertDoesNotThrow(()->emit(event,e->{throw new IllegalStateException(PRIVATE);}));
        assertDoesNotThrow(()->failure(Stage.HTTP_WORK,new RuntimeException(PRIVATE){@Override public synchronized Throwable getCause(){throw new IllegalStateException(PRIVATE);}}));
        assertDoesNotThrow(()->failure(Stage.SQL_READ,new KbFailure(404,PRIVATE)));
        assertDoesNotThrow(()->sql(Stage.SQL_WRITE,PRIVATE));
        assertThrows(NullPointerException.class,()->new Event(null,Failure.IO,SqlState.NONE));
        assertThrows(NullPointerException.class,()->new Event(Stage.HTTP_WORK,null,SqlState.NONE));
        assertThrows(NullPointerException.class,()->new Event(Stage.HTTP_WORK,Failure.IO,null));
    }
    @Test void actualPrivateLoggerReceivesOnlyFiniteLinesAndNeverThrowable(){
        var logger=java.util.logging.Logger.getLogger("org.documentknowledge.knowledgebase.failure");
        var lines=new ArrayList<String>();var thrown=new ArrayList<Throwable>();
        var handler=new java.util.logging.Handler(){public void publish(java.util.logging.LogRecord record){lines.add(record.getMessage());thrown.add(record.getThrown());}public void flush(){}public void close(){}};
        boolean previousParents=logger.getUseParentHandlers();var previousLevel=logger.getLevel();logger.setUseParentHandlers(false);logger.setLevel(java.util.logging.Level.ALL);logger.addHandler(handler);
        try{
            failure(Stage.SHARE_HTTP,new IOException(PRIVATE));sql(Stage.SQL_WRITE,"23514");sql(Stage.SQL_READ,PRIVATE);failure(Stage.HTTP_WORK,new KbFailure(404,PRIVATE));shareResponse(404);shareResponse(200);
            assertEquals(java.util.List.of("kb_failure stage=SHARE_HTTP category=IO sql_state=NONE","kb_failure stage=SQL_WRITE category=SQL sql_state=CHECK_23514","kb_failure stage=SQL_READ category=SQL sql_state=OTHER","kb_share_response status=NOT_FOUND"),lines);
            assertTrue(thrown.stream().allMatch(java.util.Objects::isNull));
        }finally{logger.removeHandler(handler);logger.setUseParentHandlers(previousParents);logger.setLevel(previousLevel);}
    }
    @Test void boundedCausalTraversalNeverRendersCauseAndHandlesSelfReference(){
        var cyclic=new RuntimeException(PRIVATE){@Override public synchronized Throwable getCause(){return this;}};
        assertEquals(Failure.UNEXPECTED,classify(Stage.SHARE_HTTP,cyclic).failure());
        Throwable deep=new TimeoutException(PRIVATE);for(int i=0;i<5;i++)deep=new RuntimeException(PRIVATE,deep);
        assertEquals(Failure.UNEXPECTED,classify(Stage.SHARE_HTTP,deep).failure());
        assertEquals(Failure.UNEXPECTED,classify(Stage.SHARE_HTTP,null).failure());
    }
    @Test void sharingStatusesAreClosedAndLoggingFailureCannotAlterOutcome(){
        var lines=new ArrayList<String>();
        int[] statuses={403,404,409,400,401,413,415,422,429,500,502,503,504};
        for(int i=0;i<statuses.length;i++){
            shareResponse(statuses[i],lines::add);
            assertEquals("kb_share_response status="+ShareStatus.values()[i].name(),lines.get(i));
        }
        int before=lines.size();shareResponse(200,lines::add);assertEquals(before,lines.size());
        for(int status:new int[]{Integer.MIN_VALUE,-1,0,199,201,301,302,307,418,599,600,Integer.MAX_VALUE}){
            shareResponse(status,lines::add);assertEquals("kb_share_response status=OTHER",lines.getLast());
        }
        assertTrue(lines.stream().allMatch(line->line.matches("kb_share_response status=[A-Z_]+")));
        assertDoesNotThrow(()->shareResponse(503,line->{throw new IllegalStateException(PRIVATE);}));
        assertDoesNotThrow(()->shareResponse(503,null));
    }

}
