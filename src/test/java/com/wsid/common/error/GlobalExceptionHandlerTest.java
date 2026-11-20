package com.wsid.common.error;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;
class GlobalExceptionHandlerTest {
 @Test void translatesOnlyKnownConstraints(){
  var handler=new GlobalExceptionHandler();
  var request=new DataIntegrityViolationException("hidden",new ConstraintViolationException("hidden",new SQLException(),"uq_training_messages_request"));
  assertEquals("DUPLICATE_REQUEST",handler.handleIntegrity(request).getBody().code());
  var sequence=new DataIntegrityViolationException("hidden",new ConstraintViolationException("hidden",new SQLException(),"uq_training_messages_sequence"));
  assertEquals("SESSION_VERSION_CONFLICT",handler.handleIntegrity(sequence).getBody().code());
  var other=new DataIntegrityViolationException("hidden",new ConstraintViolationException("hidden",new SQLException(),"unrelated_constraint"));
  assertSame(other,assertThrows(DataIntegrityViolationException.class,()->handler.handleIntegrity(other)));
 }
}
