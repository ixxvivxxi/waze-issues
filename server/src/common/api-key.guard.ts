import {
  CanActivate,
  ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import type { Request } from 'express';

@Injectable()
export class ApiKeyGuard implements CanActivate {
  constructor(private readonly config: ConfigService) {}

  canActivate(context: ExecutionContext): boolean {
    const expected = (this.config.get<string>('apiKey') ?? '').trim();
    if (!expected) {
      throw new UnauthorizedException('API_KEY is not configured on the server');
    }
    const req = context.switchToHttp().getRequest<Request>();
    const provided =
      (req.header('x-api-key') ?? req.header('X-Api-Key') ?? '').trim();
    if (!provided || provided !== expected) {
      throw new UnauthorizedException('Invalid or missing X-Api-Key');
    }
    return true;
  }
}
